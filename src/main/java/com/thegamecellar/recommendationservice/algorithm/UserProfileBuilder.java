package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.library.UserGameDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
