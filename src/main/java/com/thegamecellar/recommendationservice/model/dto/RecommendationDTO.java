package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RecommendationDTO {
    private Integer rawgId;
    private String name;
    private BigDecimal rating;
    private String backgroundImage;
    private List<String> genres;
    private List<String> platforms;
    private String reason;
    private Integer tier;
}
