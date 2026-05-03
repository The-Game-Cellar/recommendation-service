package com.thegamecellar.recommendationservice.model.dto;

import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/recommendations/personalized/grouped}. Same body
 * shape as the flat {@code /personalized} endpoint minus {@code limit} — the row layout has
 * a fixed shape (8 rows × {@link com.thegamecellar.recommendationservice.service.RecommendationService#GROUPED_PER_ROW} games).
 */
@Data
public class GroupedRequest {
    private List<Integer> recentlyShownIds;
}
