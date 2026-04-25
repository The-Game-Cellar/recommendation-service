package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BecauseYouLikedDTO {
    private Integer basedOnIgdbId;
    private String basedOnGame;
    private List<RecommendationDTO> recommendations;
}