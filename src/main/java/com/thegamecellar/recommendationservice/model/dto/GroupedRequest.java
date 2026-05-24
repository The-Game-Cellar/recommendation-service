package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

// Row layout has fixed shape so no limit field (vs PersonalizedRequest).
@Data
public class GroupedRequest {
    @Size(max = 5000)
    private List<Integer> recentlyShownIds;
}
