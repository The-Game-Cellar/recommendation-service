package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public class UserProfileBuilder {

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
     */
    public static UserProfile buildMultiDim(List<UserGameDTO> ratedGames) {
        if (ratedGames == null || ratedGames.isEmpty()) {
            return new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), 0);
        }
        Map<String, Double> genres = accumulate(ratedGames, UserGameDTO::getGenres);
        Map<String, Double> themes = accumulate(ratedGames, UserGameDTO::getThemes);
        Map<String, Double> tags = accumulate(ratedGames, UserGameDTO::getTags);
        return new UserProfile(genres, themes, tags, ratedGames.size());
    }

    /**
     * Profile weight per rated game. Ratings of 5 or below contribute nothing — those are
     * ambivalent or actively-disliked titles and shouldn't shape recommendations. Above 5,
     * the contribution is {@code rating - 5} so a 9★ game weighs 4× as much as a 6★ game
     * (was 1.5× when raw rating was used as weight, which let mediocre ratings dilute the
     * profile signal).
     */
    private static double weightFor(int rating) {
        return rating > 5 ? (rating - 5) : 0.0;
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
     * lower-weighted entries can still be sampled occasionally — preserving
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
