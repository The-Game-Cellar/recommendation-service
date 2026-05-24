package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecommendationRow {
    private String label;
    private String genre;
    private boolean fallback;
    private List<RecommendationDTO> games;
}
