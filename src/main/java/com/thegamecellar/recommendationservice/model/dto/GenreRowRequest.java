package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GenreRowRequest {
    @NotBlank
    @Size(max = 100)
    private String genre;

    @Size(max = 5000)
    private List<Integer> recentlyShownIds;
}
