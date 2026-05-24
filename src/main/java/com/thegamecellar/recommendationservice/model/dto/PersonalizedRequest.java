package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

// POST + body: recentlyShownIds grows uncapped per session and would blow Tomcat's 8KB header buffer as a query string.
@Data
public class PersonalizedRequest {

    @Min(1)
    @Max(100)
    private int limit = 10;

    @Size(max = 5000)
    private List<Integer> recentlyShownIds;
}
