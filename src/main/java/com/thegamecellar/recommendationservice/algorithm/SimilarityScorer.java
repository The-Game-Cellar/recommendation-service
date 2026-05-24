package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimilarityScorer {

    private static final double GENRE_MATCH_SCORE = 2.0;
    private static final double HIGH_RATING_BONUS = 1.0;
    private static final double HIGH_RATING_THRESHOLD = 8.0;

    // ALPHA=genre (sanity bucket), BETA=theme, GAMMA=tag (sub-genre signal), DELTA=rating prior.
    static final double ALPHA = 0.30;
    static final double BETA = 0.20;
    static final double GAMMA = 0.40;
    static final double DELTA = 0.10;

    // 0.15 puts platform on the same magnitude as the rating prior. Set 0 to neutralise the layer.
    public static final double EPSILON = 0.15;

    // Mirrors EPSILON so release-year match lifts equally. Set 0 to disable the layer.
    public static final double RELEASE_YEAR_EPSILON = 0.15;

    private SimilarityScorer() {}

    public static double score(GameDTO candidate, Map<String, Double> genreProfile) {
        if (candidate.getGenres() == null || genreProfile.isEmpty()) {
            return 0.0;
        }

        double score = 0.0;
        for (String genre : candidate.getGenres()) {
            Double userRating = genreProfile.get(genre);
            if (userRating != null) {
                score += GENRE_MATCH_SCORE;
                if (userRating >= HIGH_RATING_THRESHOLD) {
                    score += HIGH_RATING_BONUS;
                }
            }
        }
        return score;
    }

    public static double scoreByGenreOverlap(GameDTO candidate, List<String> referenceGenres) {
        if (candidate.getGenres() == null || referenceGenres == null) {
            return 0.0;
        }
        return candidate.getGenres().stream()
                .filter(referenceGenres::contains)
                .count();
    }

    // Tags weigh heaviest so two RPGs with different sub-genres (Dark Souls vs Witcher 3) score differently.
    public static double scoreMultiDim(GameDTO candidate, UserProfile profile) {
        if (candidate == null || profile == null || profile.isEmpty()) return 0.0;
        double genre = cosineOverlap(candidate.getGenres(), profile.genres());
        double theme = cosineOverlap(candidate.getThemes(), profile.themes());
        double tag = cosineOverlap(candidate.getTags(), profile.tags());
        double prior = ratingPrior(candidate.getTotalRating(), candidate.getRating(), candidate.getTotalRatingCount());
        return ALPHA * genre + BETA * theme + GAMMA * tag + DELTA * prior;
    }

    // Treats candidate features as binary (1.0); user-side magnitude carries the signal.
    private static double cosineOverlap(List<String> candidateFeatures, Map<String, Double> profile) {
        if (candidateFeatures == null || candidateFeatures.isEmpty()) return 0.0;
        if (profile == null || profile.isEmpty()) return 0.0;

        Set<String> seen = new HashSet<>();
        double dot = 0.0;
        int candidateCount = 0;
        for (String f : candidateFeatures) {
            if (f == null) continue;
            String key = f.trim();
            if (key.isEmpty() || !seen.add(key)) continue;
            candidateCount++;
            Double w = profile.get(key);
            if (w != null) dot += w;
        }
        if (candidateCount == 0) return 0.0;
        double normP = 0.0;
        for (Double w : profile.values()) {
            if (w == null) continue;
            normP += w * w;
        }
        if (normP == 0.0) return 0.0;
        return dot / (Math.sqrt(candidateCount) * Math.sqrt(normP));
    }

    // Binary boost; reads only declaredBuckets so rating-evidence buckets never leak into the boost on heavy libraries.
    public static double releaseYearBoost(GameDTO candidate, Set<String> declaredBuckets) {
        if (candidate == null || declaredBuckets == null || declaredBuckets.isEmpty()) return 0.0;
        String bucket = UserProfileBuilder.releaseYearBucket(candidate.getReleased());
        if (bucket == null) return 0.0;
        return declaredBuckets.contains(bucket) ? 1.0 : 0.0;
    }

    public static double platformBoost(GameDTO candidate, Map<String, Double> profilePlatforms) {
        if (candidate == null || profilePlatforms == null || profilePlatforms.isEmpty()) return 0.0;
        List<String> candidatePlatforms = candidate.getPlatforms();
        if (candidatePlatforms == null || candidatePlatforms.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (String p : candidatePlatforms) {
            if (p == null) continue;
            Double w = profilePlatforms.get(p);
            if (w == null) continue;
            sum += w;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    // Confidence-gated: vote count < MIN_VOTE_COUNT returns 0 so niche games with a few high votes can't dominate.
    // Maps 6.0->0, 10.0->1 (clamped) so below-average titles contribute zero prior, never a baseline floor.
    private static final int MIN_VOTE_COUNT = 10;

    private static double ratingPrior(BigDecimal totalRating, BigDecimal critic, Integer voteCount) {
        if (voteCount == null || voteCount < MIN_VOTE_COUNT) return 0.0;
        BigDecimal picked = totalRating != null ? totalRating : critic;
        if (picked == null) return 0.0;
        double r = picked.doubleValue();
        double prior = (r - 6.0) / 4.0;
        if (prior < 0.0) return 0.0;
        if (prior > 1.0) return 1.0;
        return prior;
    }
}
