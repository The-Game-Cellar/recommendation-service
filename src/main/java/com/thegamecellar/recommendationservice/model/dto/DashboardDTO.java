package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DashboardDTO {
    private List<RecommendationDTO> recommendations;
    private List<RecommendationDTO> wildcard;
    private List<BecauseYouLikedDTO> becauseYouLiked;
}