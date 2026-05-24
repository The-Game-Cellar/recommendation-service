package com.thegamecellar.recommendationservice.algorithm;

import com.thegamecellar.recommendationservice.model.dto.game.GameDTO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MMRReRankerTest {

    @Test
    void reRank_returns_empty_list_for_empty_input() {
        UserProfile profile = new UserProfile(Map.of("RPG", 5.0), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 1);

        assertThat(MMRReRanker.reRank(List.of(), profile, 5)).isEmpty();
    }

    @Test
    void reRank_returns_empty_list_for_zero_k() {
        UserProfile profile = new UserProfile(Map.of("RPG", 5.0), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 1);
        List<GameDTO> games = List.of(game(1, "RPG"));

        assertThat(MMRReRanker.reRank(games, profile, 0)).isEmpty();
    }

    @Test
    void reRank_caps_at_input_size_when_k_exceeds_input() {
        UserProfile profile = new UserProfile(Map.of("RPG", 5.0), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 1);
        List<GameDTO> games = List.of(game(1, "RPG"), game(2, "Action"));

        assertThat(MMRReRanker.reRank(games, profile, 10)).hasSize(2);
    }

    @Test
    void reRank_first_pick_is_top_relevance() {
        UserProfile profile = new UserProfile(
                Map.of("RPG", 9.0),
                new HashMap<>(),
                Map.of("souls-like", 9.0),
                new HashMap<>(),
                new HashMap<>(),
                Set.of(),
                1);

        // Pre-sorted by relevance descending: souls-like RPG first
        GameDTO mostRelevant = new GameDTO();
        mostRelevant.setIgdbId(1);
        mostRelevant.setGenres(List.of("RPG"));
        mostRelevant.setTags(List.of("souls-like"));

        GameDTO lessRelevant = new GameDTO();
        lessRelevant.setIgdbId(2);
        lessRelevant.setGenres(List.of("Strategy"));

        List<GameDTO> diversified = MMRReRanker.reRank(List.of(mostRelevant, lessRelevant), profile, 2);

        assertThat(diversified.get(0).getIgdbId()).isEqualTo(1);
    }

    @Test
    void reRank_prefers_diverse_candidates_when_relevance_is_comparable() {
        // User likes RPGs + souls-likes, AND Action + stealth (equal weight on both clusters).
        UserProfile profile = new UserProfile(
                Map.of("RPG", 5.0, "Action", 5.0),
                new HashMap<>(),
                Map.of("souls-like", 5.0, "stealth", 5.0),
                new HashMap<>(),
                new HashMap<>(),
                Set.of(),
                2);

        // Anchor: RPG souls-like (top relevance).
        GameDTO anchor = new GameDTO();
        anchor.setIgdbId(1);
        anchor.setGenres(List.of("RPG"));
        anchor.setTags(List.of("souls-like"));

        // Near-duplicate: same shape as anchor.
        GameDTO duplicate = new GameDTO();
        duplicate.setIgdbId(2);
        duplicate.setGenres(List.of("RPG"));
        duplicate.setTags(List.of("souls-like"));

        // Diverse: equal raw relevance to user (Action + stealth) but disjoint feature
        // set vs. anchor. With λ=0.7 the duplicate's near-1.0 similarity penalty makes
        // diverse the winner for slot 2.
        GameDTO diverse = new GameDTO();
        diverse.setIgdbId(3);
        diverse.setGenres(List.of("Action"));
        diverse.setTags(List.of("stealth"));

        List<GameDTO> diversified = MMRReRanker.reRank(
                List.of(anchor, duplicate, diverse), profile, 2);

        assertThat(diversified.get(0).getIgdbId()).isEqualTo(1);
        assertThat(diversified.get(1).getIgdbId()).isEqualTo(3);
    }

    @Test
    void reRank_falls_back_to_top_n_when_profile_empty() {
        UserProfile empty = new UserProfile(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), 0);
        List<GameDTO> games = List.of(game(1, "RPG"), game(2, "Action"), game(3, "Strategy"));

        List<GameDTO> result = MMRReRanker.reRank(games, empty, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getIgdbId()).isEqualTo(1);
        assertThat(result.get(1).getIgdbId()).isEqualTo(2);
    }

    private GameDTO game(int id, String genre) {
        GameDTO g = new GameDTO();
        g.setIgdbId(id);
        g.setGenres(List.of(genre));
        return g;
    }
}
