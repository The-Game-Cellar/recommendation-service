package com.thegamecellar.recommendationservice.model.dto.library;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
public class UserGameDTO {
    private Long id;
    private Integer igdbGameId;
    private String gameName;
    private String status;
    private Integer rating;
    private String platform;
    private List<String> genres;
    private LocalDateTime dateAdded;
    private LocalDateTime lastPlayed;
    private Integer playtime;
    private String notes;
}