package com.thegamecellar.recommendationservice.algorithm;

import java.util.Map;
import java.util.Set;

/**
 * Multi-dimensional taste profile for the recommendation engine. Each map dimension is a
 * {@link Map} of feature name → accumulated rating weight. A user who rates The Witcher 3
 * 9★ adds {@code +9} to every entry in that game's genres / themes / tags. The
 * {@code platforms} dimension is sqrt-normalised so values sum to 1.0 across the user's
 * platforms. The {@code releaseYears} map is the evidence + declared-prior blended bucket
 * vector mirroring the genre / tag dimensions; {@code declaredReleaseYears} carries ONLY
 * the user's explicit Profile picks so the binary-scoring layer can boost games whose
 * release era is a declared pick without bleeding through rating-evidence buckets.
 */
public record UserProfile(
        Map<String, Double> genres,
        Map<String, Double> themes,
        Map<String, Double> tags,
        Map<String, Double> platforms,
        Map<String, Double> releaseYears,
        Set<String> declaredReleaseYears,
        int ratedGameCount
) {
    public boolean isEmpty() {
        return (genres == null || genres.isEmpty())
                && (themes == null || themes.isEmpty())
                && (tags == null || tags.isEmpty())
                && (platforms == null || platforms.isEmpty())
                && (releaseYears == null || releaseYears.isEmpty())
                && (declaredReleaseYears == null || declaredReleaseYears.isEmpty());
    }
}
