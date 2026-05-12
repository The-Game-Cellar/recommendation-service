package com.thegamecellar.recommendationservice.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/recommendations/dashboard}. Same shape and motivation as
 * {@link PersonalizedRequest}: {@code recentlyShownIds} grows uncapped with session activity
 * ("until logout" semantics) and would routinely exceed Tomcat's default header buffer if sent
 * as a query string, so the endpoint accepts a POST body instead.
 */
@Data
public class DashboardRequest {

    @Size(max = 5000)
    private List<Integer> recentlyShownIds;
}
