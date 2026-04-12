package com.thegamecellar.recommendationservice.model.dto.game;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class GameSearchDTO {
    private List<GameDTO> games;
    private Integer totalCount;
    private Integer page;
    private Integer pageSize;
}
