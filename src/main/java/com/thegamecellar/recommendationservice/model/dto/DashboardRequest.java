package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DashboardRequest {

    @Size(max = 5000)
    private List<Integer> recentlyShownIds;
}
