package com.thegamecellar.recommendationservice.model.dto.game;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
public class GameDTO {
    private Integer igdbId;
    private String name;
    private BigDecimal rating;
    private BigDecimal totalRating;
    private Integer totalRatingCount;
    private String backgroundImage;
    private String released;
    private List<String> genres;
    private List<String> platforms;
    private List<String> themes;
    private List<String> tags;
    private List<String> moods;
    private List<Integer> similarGameIds;
}
