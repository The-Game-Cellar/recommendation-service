package com.thegamecellar.recommendationservice.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class RecommendationDTO {
    private Integer igdbId;
    private String name;
    private BigDecimal rating;
    private String backgroundImage;
    private List<String> genres;
    private List<String> platforms;
    private String reason;
    private Integer tier;
    // The connection the sentence is composed from: seed plus shared tags, shared tags alone,
    // then reason. A seed without a rating is a catalog anchor ("Similar to"), not a rated game.
    private Integer seedIgdbId;
    private String seedName;
    private Integer seedRating;
    @Builder.Default
    private List<String> sharedTags = List.of();
}
