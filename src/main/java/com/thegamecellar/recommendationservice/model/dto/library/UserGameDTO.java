package com.thegamecellar.recommendationservice.model.dto.library;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class UserGameDTO {
    private Long id;
    private Integer rawgGameId;
    private String gameName;
    private String status;
    private Integer rating;
    private String platform;
    private LocalDateTime dateAdded;
    private LocalDateTime lastPlayed;
    private Integer playtime;
    private String notes;
}