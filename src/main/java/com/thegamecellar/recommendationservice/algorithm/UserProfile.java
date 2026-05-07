package com.thegamecellar.recommendationservice.algorithm;

import java.util.Map;

/**
 * Multi-dimensional taste profile for the recommendation engine. Each dimension is a
 * {@link Map} of feature name → accumulated rating weight. A user who rates The Witcher 3
 * 9★ adds {@code +9} to every entry in that game's genres / themes / tags. The
 * {@code platforms} dimension is sqrt-normalised so values sum to 1.0 across the user's
 * platforms — used as a multiplier-style boost rather than a raw weight, so the magnitude
 * matters relative to other platforms, not in absolute terms.
 */
public record UserProfile(
        Map<String, Double> genres,
        Map<String, Double> themes,
        Map<String, Double> tags,
        Map<String, Double> platforms,
        int ratedGameCount
) {
    public boolean isEmpty() {
        return (genres == null || genres.isEmpty())
                && (themes == null || themes.isEmpty())
                && (tags == null || tags.isEmpty())
                && (platforms == null || platforms.isEmpty());
    }
}
