package com.thegamecellar.recommendationservice.algorithm;

import java.util.Map;
import java.util.Set;

// declaredReleaseYears carries only explicit Profile picks; blended map would leak rating-evidence buckets into the boost.
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
