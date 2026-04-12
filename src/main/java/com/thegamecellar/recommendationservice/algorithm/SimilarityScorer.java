package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;

import java.util.List;
import java.util.Map;

public class SimilarityScorer {

    private static final double GENRE_MATCH_SCORE = 2.0;
    private static final double HIGH_RATING_BONUS = 1.0;
    private static final double HIGH_RATING_THRESHOLD = 8.0;

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
}
