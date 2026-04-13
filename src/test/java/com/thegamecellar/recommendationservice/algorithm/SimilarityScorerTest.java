package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityScorerTest {

    @Test
    void score_returns_zero_when_candidate_has_no_genres() {
        GameDTO candidate = new GameDTO();
        candidate.setGenres(null);

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 9.0));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void score_returns_zero_when_no_genres_match_profile() {
        GameDTO candidate = gameWithGenres("Strategy");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 8.0));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void score_returns_genre_match_score_on_match() {
        GameDTO candidate = gameWithGenres("RPG");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 5.0));

        assertThat(score).isEqualTo(2.0);
    }

    @Test
    void score_adds_high_rating_bonus_when_genre_rated_above_threshold() {
        GameDTO candidate = gameWithGenres("RPG");

        double score = SimilarityScorer.score(candidate, Map.of("RPG", 9.0));

        assertThat(score).isEqualTo(3.0); // 2.0 match + 1.0 bonus
    }

    @Test
    void score_increases_with_more_matching_genres() {
        GameDTO oneMatch = gameWithGenres("RPG", "Strategy");
        GameDTO twoMatches = gameWithGenres("RPG", "Action");
        Map<String, Double> profile = Map.of("RPG", 5.0, "Action", 5.0);

        assertThat(SimilarityScorer.score(twoMatches, profile))
                .isGreaterThan(SimilarityScorer.score(oneMatch, profile));
    }

    @Test
    void scoreByGenreOverlap_returns_count_of_matching_genres() {
        GameDTO candidate = gameWithGenres("RPG", "Action", "Strategy");

        double overlap = SimilarityScorer.scoreByGenreOverlap(candidate, List.of("RPG", "Action"));

        assertThat(overlap).isEqualTo(2.0);
    }

    @Test
    void scoreByGenreOverlap_returns_zero_when_no_overlap() {
        GameDTO candidate = gameWithGenres("Strategy");

        double overlap = SimilarityScorer.scoreByGenreOverlap(candidate, List.of("RPG"));

        assertThat(overlap).isEqualTo(0.0);
    }

    private GameDTO gameWithGenres(String... genres) {
        GameDTO game = new GameDTO();
        game.setGenres(List.of(genres));
        return game;
    }
}