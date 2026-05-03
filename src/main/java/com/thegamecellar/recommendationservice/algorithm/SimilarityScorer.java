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

    // Multi-dim scoring weights. Tag still carries the sub-genre signal (souls-like,
    // open-world, roguelike) but genre weighs in heavier as a sanity bucket — keeps
    // off-genre matches that happen to share a single tag from floating up. Theme is
    // mood/setting fit. Rating prior gives a small boost to highly-aggregated games.
    static final double ALPHA = 0.30;
    static final double BETA = 0.20;
    static final double GAMMA = 0.40;
    static final double DELTA = 0.10;

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

    /**
     * Multi-dimensional score. Weighted cosine similarity per dimension
     * (genre / theme / tag) plus a normalised rating prior. Higher = better match.
     * Tags dominate (γ=0.55) since they carry the sub-genre signal that distinguishes
     * Dark Souls from Witcher 3 even though both are RPG.
     */
    public static double scoreMultiDim(GameDTO candidate, UserProfile profile) {
        if (candidate == null || profile == null || profile.isEmpty()) return 0.0;
        double genre = cosineOverlap(candidate.getGenres(), profile.genres());
        double theme = cosineOverlap(candidate.getThemes(), profile.themes());
        double tag = cosineOverlap(candidate.getTags(), profile.tags());
        double prior = ratingPrior(candidate.getTotalRating(), candidate.getRating(), candidate.getTotalRatingCount());
        return ALPHA * genre + BETA * theme + GAMMA * tag + DELTA * prior;
    }

    /**
     * Cosine similarity between a candidate's binary feature vector and the user's
     * weighted profile vector. Treats every candidate feature as 1.0 — the magnitude
     * of the user side is what carries the signal.
     */
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

    /**
     * Confidence-gated rating prior. Prefers IGDB's combined critic+user score
     * ({@code totalRating}) over critic-only ({@code rating}) because the combined value
     * reflects actual player reception. Returns 0 when {@code totalRatingCount} is below
     * {@link #MIN_VOTE_COUNT} so a niche game with a handful of high votes can't masquerade
     * as a top recommendation. Maps the picked rating from the 0..10 IGDB-normalised scale
     * to a [0,1] prior: 6.0 → 0, 10.0 → 1, clamped. Below-average titles (≤ 6/10, ~60/100
     * raw IGDB) contribute zero prior, so the prior only acts as an upweight for genuinely
     * well-received candidates rather than a baseline floor.
     */
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
