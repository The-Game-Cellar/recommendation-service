package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/recommendations/personalized}. Endpoint is POST rather
 * than GET because {@code recentlyShownIds} grows linearly with session activity (uncapped
 * "until logout" semantics) and would routinely exceed Tomcat's 8KB default header buffer
 * for power users — the URL approach scales by Tomcat config tuning, the body approach by
 * Spring's request-body limit (10MB+).
 */
@Data
public class PersonalizedRequest {

    @Min(1)
    @Max(100)
    private int limit = 10;

    private List<Integer> recentlyShownIds;
}
