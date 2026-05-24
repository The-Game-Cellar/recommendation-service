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

    /**
     * Multiplier applied to a platform's raw rating-weighted count when the user has marked
     * that platform as primary in their {@code user_platforms} entry. Doubles the raw count
     * before sqrt-normalisation, so a primary-marked PS5 with raw count 69 becomes 138, lifting
     * the post-normalisation weight noticeably without erasing the user's secondary platforms.
     * Dormant when no platform is marked primary (today's default, UI to set this is a future
     * issue) and gracefully no-op for users with all platforms marked primary (multiplier
     * applied uniformly cancels in normalisation).
     */
    public static final double PRIMARY_PLATFORM_BOOST_MULTIPLIER = 2.0;

    /** Rated-count at which preference prior decays to its floor weight. Set to 0 for ratings-only. */
    public static final int PREFERENCE_BLEND_CAP = 10;

    /** Permanent floor weight of the declared-preference prior. Set to 0.0 for legacy no-floor behaviour. */
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

    /**
     * Multi-dimensional profile. Each dimension accumulates the user's rating as a weight
     * on every feature value of every rated game. Higher-rated games dominate the resulting
     * weight vectors. Consumed by the weighted-cosine scorer + MMR re-rank.
     * <p>
     * The {@code platforms} dimension is built from the per-row {@link UserGameDTO#getPlatform()}
     * field (the platform the user actually plays a game on, set when adding it to the library)
     * and post-processed through a square-root normalisation so values sum to 1.0. Sqrt damping
     * keeps a heavily-skewed library's secondary platform visible while still preferring the
     * primary: a 90 PS5 / 10 PC user gets weights {@code {PS5≈0.75, PC≈0.25}} instead of the
     * raw {@code {PS5=0.90, PC=0.10}} that would mute the secondary platform almost entirely.
     */
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames) {
        return buildMultiDim(ratedGames, List.of());
    }

    /**
     * Multi-dim profile with optional primary-platform amplification. {@code userPlatforms}
     * carries the user's onboarding-set platforms with the {@link UserPlatformDTO#getIsPrimary()}
     * flag: any platform marked primary gets its raw rating-weighted count multiplied by
     * {@link #PRIMARY_PLATFORM_BOOST_MULTIPLIER} before sqrt-normalisation, lifting its
     * post-normalisation weight without erasing secondaries. Pass an empty list (or use the
     * single-arg overload) to skip the amplification entirely.
     */
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms) {
        return buildMultiDim(ratedGames, userPlatforms, List.of());
    }

    /** Three-arg overload, delegates with empty tag-preferences. */
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms,
                                             List<String> genrePreferences) {
        return buildMultiDim(ratedGames, userPlatforms, genrePreferences, List.of());
    }

    /** Four-arg overload, delegates with empty release-year-preferences. */
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames,
                                             List<UserPlatformDTO> userPlatforms,
                                             List<String> genrePreferences,
                                             List<String> tagPreferences) {
        return buildMultiDim(ratedGames, userPlatforms, genrePreferences, tagPreferences, List.of());
    }

    /**
     * Multi-dim profile with primary-platform amplification and cold-start blends for the genre,
     * tag and release-year dimensions. Each blended dimension linearly combines a unit-normalised
     * uniform prior (declared preferences) with the unit-normalised rating evidence using
     * coefficient {@code α = min(1 - FLOOR, n / CAP)}. Theme and platform dimensions are not
     * blended. Release-year evidence is per-rated-game release-date classified into the bucket
     * labels defined by {@link #releaseYearBucket(String)}.
     */
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

        // Snapshot of the trimmed / deduped declared release-year buckets. Scoring layer
        // uses this set (not the blended releaseYears map) so the boost fires only on the
        // user's explicit picks, never on rating-evidence buckets.
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

    /** Thin alias for direct unit-test access. */
    static Map<String, Double> blendGenres(Map<String, Double> ratingGenres,
                                            List<String> preferences,
                                            int ratedCount) {
        return blendWithPrior(ratingGenres, preferences, ratedCount);
    }

    /** Thin alias for direct unit-test access. */
    static Map<String, Double> blendTags(Map<String, Double> ratingTags,
                                          List<String> preferences,
                                          int ratedCount) {
        return blendWithPrior(ratingTags, preferences, ratedCount);
    }

    /** Thin alias for direct unit-test access. */
    static Map<String, Double> blendReleaseYears(Map<String, Double> ratingReleaseYears,
                                                  List<String> preferences,
                                                  int ratedCount) {
        return blendWithPrior(ratingReleaseYears, preferences, ratedCount);
    }

    /**
     * Classifies an ISO release date string into one of the five fixed decade buckets used by
     * the declared release-year preferences. {@code null}, blank, or unparseable input returns
     * {@code null} so the accumulator and scorer skip the row gracefully. The {@code Pre-1990}
     * and {@code 2020s} buckets are intentionally open-ended on their respective sides.
     */
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

    /**
     * Each rated game contributes {@link #weightFor(int)} to the bucket its release date falls
     * into. Null / unparseable release dates skipped. Output is a raw weight map; the calling
     * blend helper normalises before combining with the declared prior.
     */
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

    /** Linearly combines unit-normalised rating evidence with a uniform preference prior. */
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

    /**
     * Divides each value by the sum so the resulting map is on a unit simplex. Null/non-positive
     * entries are dropped. Empty input → empty output.
     */
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

    /**
     * Uniform distribution over the supplied names ({@code 1/k} each, sum = 1.0). Trims and
     * dedupes; null/blank entries dropped. Empty/null input → empty output.
     */
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

    /**
     * Multiplies the raw count of platforms marked {@code is_primary = true} by
     * {@link #PRIMARY_PLATFORM_BOOST_MULTIPLIER}. Mutates the supplied map in place. No-op when
     * {@code userPlatforms} is null/empty or when no platform has the flag set. Platforms with
     * a primary flag but no rated games (raw count = 0) stay at 0; primary-marking doesn't
     * conjure data, it amplifies existing rating signal.
     */
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

    /**
     * Profile weight per rated game. Ratings of 5 or below contribute nothing; those are
     * ambivalent or actively-disliked titles and shouldn't shape recommendations. Above 5,
     * the contribution is {@code rating - 5} so a 9★ game weighs 4× as much as a 6★ game
     * (was 1.5× when raw rating was used as weight, which let mediocre ratings dilute the
     * profile signal).
     */
    private static double weightFor(int rating) {
        return rating > 5 ? (rating - 5) : 0.0;
    }

    /**
     * Accumulates rating-weight per single-string {@link UserGameDTO#getPlatform()} value. Mirrors
     * {@link #accumulate(List, Function)} but for the singular platform field rather than the list
     * dimensions. Returns raw counts; sqrt-normalisation applied in {@link #sqrtNormalise(Map)}.
     */
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

    /**
     * Sqrt-softens a raw count vector then normalises so values sum to 1.0. Standard IR damping
     * pattern (BM25 / Lucene tfNorm): compresses dynamic range so a heavily-skewed distribution
     * doesn't push the secondary entries to near-zero. Empty input → empty output.
     */
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

    /**
     * Weighted random sampling without replacement (Efraimidis-Spirakis A-Res).
     * Each entry's selection probability is proportional to its weight, but
     * lower-weighted entries can still be sampled occasionally, preserving
     * variety across requests instead of always returning the same top-K.
     *
     * @return up to k keys from profile, weighted by value
     */
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
