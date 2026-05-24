package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GroupedRecommendationsResponse {
    private List<RecommendationRow> rows;
    private int tier;
    private String emptyMessage;
}
