package com.thegamecellar.recommendationservice.algorithm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TierSelectorTest {

    @Test
    void select_returns_tier_three_when_no_rated_games() {
        assertThat(TierSelector.select(0)).isEqualTo(RecommendationTier.THREE);
    }

    @Test
    void select_returns_tier_two_at_exactly_1_rated_game() {
        assertThat(TierSelector.select(1)).isEqualTo(RecommendationTier.TWO);
    }

    @Test
    void select_returns_tier_two_for_1_to_4_rated_games() {
        assertThat(TierSelector.select(2)).isEqualTo(RecommendationTier.TWO);
        assertThat(TierSelector.select(3)).isEqualTo(RecommendationTier.TWO);
        assertThat(TierSelector.select(4)).isEqualTo(RecommendationTier.TWO);
    }

    @Test
    void select_returns_tier_one_at_exactly_5_rated_games() {
        assertThat(TierSelector.select(5)).isEqualTo(RecommendationTier.ONE);
    }

    @Test
    void select_returns_tier_one_for_more_than_5_rated_games() {
        assertThat(TierSelector.select(10)).isEqualTo(RecommendationTier.ONE);
        assertThat(TierSelector.select(100)).isEqualTo(RecommendationTier.ONE);
    }
}