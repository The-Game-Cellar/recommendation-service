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
    // open-world, roguelike) but genre weighs in heavier as a sanity bucket; keeps
    // off-genre matches that happen to share a single tag from floating up. Theme adds
    // setting fit (fantasy / horror / sci-fi). Rating prior gives a small boost to
    // highly-aggregated games.
    static final double ALPHA = 0.30;
    static final double BETA = 0.20;
    static final double GAMMA = 0.40;
    static final double DELTA = 0.10;

    /**
     * Platform-boost coefficient. Score range from {@link #scoreMultiDim} is roughly [0, 1]
     * (sum of weighted-cosine terms + clamped rating prior); the platform profile is
     * sqrt-normalised in {@code UserProfileBuilder} so {@link #platformBoost} returns a value
     * in [0, 1] too. 0.15 places the platform contribution on the same magnitude as the
     * rating prior: meaningful but not dominating. Set to 0 to neutralise the entire
     * platform layer without code revert.
     */
    public static final double EPSILON = 0.15;

    /**
     * Release-year boost coefficient. Matches {@link #EPSILON} so a release-year bucket match
     * lifts a candidate by the same magnitude as a platform-profile match. Set to 0 to disable
     * the entire release-year scoring layer without removing the data path.
     */
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
     * weighted profile vector. Treats every candidate feature as 1.0; the magnitude
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
     * Weighted average of profile-platform weights across the intersection of the candidate's
     * catalog platforms and the user's sqrt-normalised platform profile. Returns a value in
     * {@code [0, 1]}:
     * <ul>
     *   <li>Pure primary-platform release → full primary weight (e.g. {@code 0.75} for a 90/10 PS5/PC user).</li>
     *   <li>Cross-platform release on primary + secondary → average of the two profile weights
     *       ({@code (0.75 + 0.25) / 2 = 0.50} above): a structural mild penalty for breadth.</li>
     *   <li>Pure secondary-platform release → secondary weight ({@code 0.25}).</li>
     *   <li>No catalog-platform in the user profile → {@code 0.0}.</li>
     * </ul>
     * <p>
     * The average self-scales to library skew: a 95/5 user gets a wide gap between pure-primary
     * and cross-platform candidates, a 51/49 user gets nearly no gap (no real "primary"), and a
     * single-platform user degenerates to a no-op (singleton average = same value, every
     * filter-passing candidate gets identical boost, ranking unchanged).
     * <p>
     * Profile-platforms not present in the candidate's catalog list are simply not in the
     * intersection and don't pull the average down. Catalog platforms not in the profile are
     * skipped (they contribute nothing, neither boost nor penalty).
     */
    /**
     * Binary in/out release-year boost against the user's declared bucket picks. Classifies the
     * candidate's release date into one of the five decade buckets via
     * {@link UserProfileBuilder#releaseYearBucket(String)} and returns {@code 1.0} when that
     * bucket is in {@code declaredBuckets}, else {@code 0.0}. The declared set is intentionally
     * separate from the blended rating-evidence map so the boost only fires on user picks and
     * does not leak through rating-evidence buckets a heavy-library user would otherwise
     * inadvertently boost across the board.
     */
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
