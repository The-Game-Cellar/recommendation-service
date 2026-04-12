package com.thegamecellar.recommendationservice.algorithm;

public class TierSelector {

    private TierSelector() {}

    public static RecommendationTier select(int ratedGameCount) {
        if (ratedGameCount >= 5) {
            return RecommendationTier.ONE;
        } else if (ratedGameCount >= 1) {
            return RecommendationTier.TWO;
        } else {
            return RecommendationTier.THREE;
        }
    }
}
