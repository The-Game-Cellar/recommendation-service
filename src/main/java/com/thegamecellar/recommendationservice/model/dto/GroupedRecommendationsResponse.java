package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Row-based recommendation payload. Tier 1 / Tier 2 users get genre-titled rows ranked by
 * their rated-library composition; Tier 3 (no rated games) gets a single "Popular" row plus
 * an onboarding nudge in {@code emptyMessage}.
 */
@Data
@Builder
public class GroupedRecommendationsResponse {
    private List<RecommendationRow> rows;
    private int tier;
    private String emptyMessage;
}
