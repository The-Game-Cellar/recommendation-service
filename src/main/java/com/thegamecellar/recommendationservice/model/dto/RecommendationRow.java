package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One row in the grouped /personalized response. Each row is a single signal source
 * (a genre or "Popular") so the user can tell at a glance why each game surfaces.
 */
@Data
@Builder
public class RecommendationRow {
    private String label;
    private String genre;
    private boolean fallback;
    private List<RecommendationDTO> games;
}
