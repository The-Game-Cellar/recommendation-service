package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;
import com.thegamecellar.recommendationservice.model.dto.library.UserPlatformDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

public class UserProfileBuilder {

    // 2.0x raw count before sqrt-normalise on primary-marked platforms; cancels uniformly when all are primary.
    public static final double PRIMARY_PLATFORM_BOOST_MULTIPLIER = 2.0;

    // Rated-count at which preference prior decays to floor; 0 = ratings-only.
    public static final int PREFERENCE_BLEND_CAP = 10;

    // Permanent floor weight of the declared-preference prior; 0.0 = legacy no-floor behaviour.
    public static final double PREFERENCE_BLEND_FLOOR = 0.15;

    private UserProfileBuilder() {}

    public static Map<String, Double> build(List<UserGameDTO> ratedGames) {
        Map<String, List<Integer>> genreRatings = new HashMap<>();

        for (UserGameDTO ratedGame : ratedGames) {
            if (ratedGame.getGenres() == null || ratedGame.getGenres().isEmpty()) {
                continue;
            }
            for (String genre : ratedGame.getGenres()) {
                genreRatings.computeIfAbsent(genre, k -> new ArrayList<>()).add(ratedGame.getRating());
            }
        }

        Map<String, Double> profile = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : genreRatings.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
            profile.put(entry.getKey(), avg);
        }
        return profile;
    }

    // platforms dim uses sqrt-normalisation so a 90/10 skew becomes ~75/25 (secondary stays visible).
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames) {
        return buildMultiDim(ratedGames, List.of());
    }

    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms) {
        return buildMultiDim(ratedGames, userPlatforms, List.of());
    }

    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms,
                                             List<String> genrePreferences) {
        return buildMultiDim(ratedGames, userPlatforms, genrePreferences, List.of());
    }

    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms,
                                             List<String> genrePreferences,
                                             List<String> tagPreferences) {
        return buildMultiDim(ratedGames, userPlatforms, genrePreferences, tagPreferences, List.of());
    }

    // Cold-start blend: α = min(1 - FLOOR, n / CAP) linearly mixes unit-normalised prior with rating evidence.
    // Theme + platform dimensions skip the blend. Release-year evidence buckets via releaseYearBucket().
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms,
                                             List<String> genrePreferences,
                                             List<String> tagPreferences,
                                             List<String> releaseYearPreferences) {
        boolean hasRated = ratedGames != null && !ratedGames.isEmpty();
        boolean hasGenrePrefs = genrePreferences != null && !genrePreferences.isEmpty();
        boolean hasTagPrefs = tagPreferences != null && !tagPreferences.isEmpty();
        boolean hasReleaseYearPrefs = releaseYearPreferences != null && !releaseYearPreferences.isEmpty();

        if (!hasRated && !hasGenrePrefs && !hasTagPrefs && !hasReleaseYearPrefs) {
            return new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 0);
        }

        int ratedCount = ratedGames == null ? 0 : ratedGames.size();

        Map<String, Double> rawGenres = hasRated ? accumulate(ratedGames, UserGameDTO::getGenres) : new HashMap<>();
        Map<String, Double> rawTags = hasRated ? accumulate(ratedGames, UserGameDTO::getTags) : new HashMap<>();
        Map<String, Double> themes = hasRated ? accumulate(ratedGames, UserGameDTO::getThemes) : new HashMap<>();
        Map<String, Double> rawReleaseYears = hasRated ? accumulateReleaseYears(ratedGames) : new HashMap<>();

        Map<String, Double> genres = hasGenrePrefs
                ? blendGenres(rawGenres, genrePreferences, ratedCount)
                : rawGenres;

        Map<String, Double> tags = hasTagPrefs
                ? blendTags(rawTags, tagPreferences, ratedCount)
                : rawTags;

        Map<String, Double> releaseYears = hasReleaseYearPrefs
                ? blendReleaseYears(rawReleaseYears, releaseYearPreferences, ratedCount)
                : rawReleaseYears;

        // Scorer reads this snapshot (not the blended map) so the boost fires only on explicit picks.
        Set<String> declaredReleaseYears = hasReleaseYearPrefs
                ? releaseYearPreferences.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();

        Map<String, Double> rawPlatforms = hasRated ? accumulatePlatform(ratedGames) : new HashMap<>();
        applyPrimaryBoost(rawPlatforms, userPlatforms);
        Map<String, Double> platforms = sqrtNormalise(rawPlatforms);

        return new UserProfile(genres, themes, tags, platforms, releaseYears, declaredReleaseYears, ratedCount);
    }

    // Aliases below exist for direct unit-test access.
    static Map<String, Double> blendGenres(Map<String, Double> ratingGenres,
                                            List<String> preferences,
                                            int ratedCount) {
        return blendWithPrior(ratingGenres, preferences, ratedCount);
    }

    static Map<String, Double> blendTags(Map<String, Double> ratingTags,
                                          List<String> preferences,
                                          int ratedCount) {
        return blendWithPrior(ratingTags, preferences, ratedCount);
    }

    static Map<String, Double> blendReleaseYears(Map<String, Double> ratingReleaseYears,
                                                  List<String> preferences,
                                                  int ratedCount) {
        return blendWithPrior(ratingReleaseYears, preferences, ratedCount);
    }

    // Pre-1990 and 2020s are open-ended on their respective sides.
    public static String releaseYearBucket(String releasedIso) {
        if (releasedIso == null || releasedIso.length() < 4) return null;
        int year;
        try {
            year = Integer.parseInt(releasedIso.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (year < 1990) return "Pre-1990";
        if (year < 2000) return "1990s";
        if (year < 2010) return "2000s";
        if (year < 2020) return "2010s";
        return "2020s";
    }

    private static Map<String, Double> accumulateReleaseYears(List<UserGameDTO> games) {
        Map<String, Double> totals = new HashMap<>();
        for (UserGameDTO g : games) {
            if (g.getRating() == null) continue;
            double weight = weightFor(g.getRating());
            if (weight <= 0.0) continue;
            String bucket = releaseYearBucket(g.getReleased());
            if (bucket == null) continue;
            totals.merge(bucket, weight, Double::sum);
        }
        return totals;
    }

    private static Map<String, Double> blendWithPrior(Map<String, Double> ratingWeights,
                                                       List<String> preferences,
                                                       int ratedCount) {
        Map<String, Double> ratingNorm = normaliseToUnit(ratingWeights);
        Map<String, Double> preferenceNorm = uniformOver(preferences);

        if (ratingNorm.isEmpty() && preferenceNorm.isEmpty()) {
            return new HashMap<>();
        }

        double alphaCap = 1.0 - PREFERENCE_BLEND_FLOOR;
        double alpha = PREFERENCE_BLEND_CAP <= 0
                ? 1.0
                : Math.min(alphaCap, (double) ratedCount / (double) PREFERENCE_BLEND_CAP);

        Set<String> keys = new HashSet<>();
        keys.addAll(ratingNorm.keySet());
        keys.addAll(preferenceNorm.keySet());

        Map<String, Double> blended = new HashMap<>(keys.size() * 2);
        for (String key : keys) {
            double r = ratingNorm.getOrDefault(key, 0.0);
            double p = preferenceNorm.getOrDefault(key, 0.0);
            double w = alpha * r + (1.0 - alpha) * p;
            if (w > 0.0) {
                blended.put(key, w);
            }
        }
        return blended;
    }

    static Map<String, Double> normaliseToUnit(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) return new HashMap<>();
        double sum = 0.0;
        for (Double v : raw.values()) {
            if (v == null || v <= 0.0) continue;
            sum += v;
        }
        if (sum == 0.0) return new HashMap<>();
        Map<String, Double> out = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            Double v = e.getValue();
            if (v == null || v <= 0.0) continue;
            out.put(e.getKey(), v / sum);
        }
        return out;
    }

    static Map<String, Double> uniformOver(List<String> names) {
        if (names == null || names.isEmpty()) return new HashMap<>();
        Set<String> deduped = new HashSet<>();
        for (String n : names) {
            if (n == null) continue;
            String trimmed = n.trim();
            if (trimmed.isEmpty()) continue;
            deduped.add(trimmed);
        }
        if (deduped.isEmpty()) return new HashMap<>();
        double weight = 1.0 / deduped.size();
        Map<String, Double> out = new HashMap<>(deduped.size() * 2);
        for (String name : deduped) {
            out.put(name, weight);
        }
        return out;
    }

    // Mutates in place. Primary-marked platforms with zero rating evidence stay at zero; this amplifies signal, not conjures it.
    private static void applyPrimaryBoost(Map<String, Double> rawCounts,
                                           List<UserPlatformDTO> userPlatforms) {
        if (userPlatforms == null || userPlatforms.isEmpty()) return;
        Set<String> primaryNames = userPlatforms.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsPrimary()))
                .map(UserPlatformDTO::getPlatformName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (primaryNames.isEmpty()) return;
        for (String name : primaryNames) {
            rawCounts.computeIfPresent(name, (k, v) -> v * PRIMARY_PLATFORM_BOOST_MULTIPLIER);
        }
    }

    // Ratings <= 5 contribute zero (ambivalent / actively-disliked). 9-star weighs 4x a 6-star (rating-5).
    private static double weightFor(int rating) {
        return rating > 5 ? (rating - 5) : 0.0;
    }

    private static Map<String, Double> accumulatePlatform(List<UserGameDTO> games) {
        Map<String, Double> totals = new HashMap<>();
        for (UserGameDTO g : games) {
            if (g.getRating() == null) continue;
            double weight = weightFor(g.getRating());
            if (weight <= 0.0) continue;
            String platform = g.getPlatform();
            if (platform == null) continue;
            String key = platform.trim();
            if (key.isEmpty()) continue;
            totals.merge(key, weight, Double::sum);
        }
        return totals;
    }

    // BM25 / Lucene tfNorm-style damping: sqrt-soften then normalise so skewed dists don't crush secondaries to zero.
    private static Map<String, Double> sqrtNormalise(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) return new HashMap<>();
        double sumSqrt = 0.0;
        for (Double v : raw.values()) {
            if (v == null || v <= 0.0) continue;
            sumSqrt += Math.sqrt(v);
        }
        if (sumSqrt == 0.0) return new HashMap<>();
        Map<String, Double> normalised = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            Double v = e.getValue();
            if (v == null || v <= 0.0) continue;
            normalised.put(e.getKey(), Math.sqrt(v) / sumSqrt);
        }
        return normalised;
    }

    private static Map<String, Double> accumulate(List<UserGameDTO> games,
                                                   Function<UserGameDTO, List<String>> extractor) {
        Map<String, Double> totals = new HashMap<>();
        for (UserGameDTO g : games) {
            if (g.getRating() == null) continue;
            double weight = weightFor(g.getRating());
            if (weight <= 0.0) continue;
            List<String> values = extractor.apply(g);
            if (values == null || values.isEmpty()) continue;
            for (String v : values) {
                if (v == null) continue;
                String key = v.trim();
                if (key.isEmpty()) continue;
                totals.merge(key, weight, Double::sum);
            }
        }
        return totals;
    }

    // Efraimidis-Spirakis A-Res: weighted sampling without replacement. Low-weight entries still occasionally sampled.
    public static List<String> sampleWeighted(Map<String, Double> profile, int k) {
        if (profile == null || profile.isEmpty() || k <= 0) {
            return List.of();
        }
        return profile.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(e -> {
                    double u = Math.max(ThreadLocalRandom.current().nextDouble(), 1e-12);
                    double key = Math.pow(u, 1.0 / e.getValue());
                    return Map.entry(e.getKey(), key);
                })
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k)
                .map(Map.Entry::getKey)
                .toList();
    }
}
