package com.thegamecellar.recommendationservice.algorithm;

import java.util.Map;

/**
 * Multi-dimensional taste profile for the recommendation engine. Each dimension is a
 * {@link Map} of feature name → accumulated rating weight. A user who rates The Witcher 3
 * 9★ adds {@code +9} to every entry in that game's genres / themes / tags.
 */
public record UserProfile(
        Map<String, Double> genres,
        Map<String, Double> themes,
        Map<String, Double> tags,
        int ratedGameCount
) {
    public boolean isEmpty() {
        return (genres == null || genres.isEmpty())
                && (themes == null || themes.isEmpty())
                && (tags == null || tags.isEmpty());
    }
}
