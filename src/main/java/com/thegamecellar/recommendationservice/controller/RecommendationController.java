package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.DashboardRequest;
import com.thegamecellar.recommendationservice.model.dto.BecauseYouLikedDTO;
import com.thegamecellar.recommendationservice.model.dto.GenreRowRequest;
import com.thegamecellar.recommendationservice.model.dto.GroupedRecommendationsResponse;
import com.thegamecellar.recommendationservice.model.dto.GroupedRequest;
import com.thegamecellar.recommendationservice.model.dto.RecommendationRow;
import com.thegamecellar.recommendationservice.model.dto.PersonalizedRequest;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.service.DashboardService;
import com.thegamecellar.recommendationservice.service.RecommendationService;
import com.thegamecellar.recommendationservice.service.SimilarGameService;
import com.thegamecellar.recommendationservice.service.WildCardService;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final WildCardService wildCardService;
    private final SimilarGameService similarGameService;
    private final DashboardService dashboardService;

    // POST not GET: recentlyShownIds grows uncapped per session and would blow Tomcat's HTTP header buffer as a query string.
    @PostMapping("/personalized")
    public ResponseEntity<List<RecommendationDTO>> getPersonalized(
            Authentication authentication,
            @Valid @RequestBody PersonalizedRequest request) {
        String userId = JwtUtils.getUserId(authentication);
        String token = JwtUtils.getBearerToken(authentication);
        Set<Integer> recent = request.getRecentlyShownIds() == null ? null : new HashSet<>(request.getRecentlyShownIds());
        return ResponseEntity.ok(recommendationService.getPersonalized(userId, token, request.getLimit(), recent));
    }

    @PostMapping("/personalized/grouped")
    public ResponseEntity<GroupedRecommendationsResponse> getPersonalizedGrouped(
            Authentication authentication,
            @Valid @RequestBody(required = false) GroupedRequest request) {
        String userId = JwtUtils.getUserId(authentication);
        String token = JwtUtils.getBearerToken(authentication);
        Set<Integer> recent = (request == null || request.getRecentlyShownIds() == null)
                ? null
                : new HashSet<>(request.getRecentlyShownIds());
        return ResponseEntity.ok(recommendationService.getPersonalizedGrouped(userId, token, recent));
    }

    // Per-row refresh button on /recommendations. Returns one bucket (15 games) + enqueues a
    // per-genre top-up so the next click serves strictly fresh ids.
    @PostMapping("/personalized/grouped/genre")
    public ResponseEntity<RecommendationRow> refreshGenreRow(
            Authentication authentication,
            @Valid @RequestBody GenreRowRequest request) {
        String userId = JwtUtils.getUserId(authentication);
        Set<Integer> recent = request.getRecentlyShownIds() == null
                ? null
                : new HashSet<>(request.getRecentlyShownIds());
        RecommendationRow row = recommendationService.getGenreRow(userId, request.getGenre(), recent);
        if (row == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(row);
    }

    @GetMapping("/wildcard")
    public ResponseEntity<List<RecommendationDTO>> getWildCard(
            Authentication authentication,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(wildCardService.getWildCard(token, limit));
    }

    @GetMapping("/similar/{gameId}")
    public ResponseEntity<List<RecommendationDTO>> getSimilar(
            @PathVariable @Min(1) Integer gameId,
            Authentication authentication,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(similarGameService.getSimilar(gameId, token, limit));
    }

    @GetMapping("/because-you-liked/{gameId}")
    public ResponseEntity<List<RecommendationDTO>> getBecauseYouLiked(
            @PathVariable @Min(1) Integer gameId,
            Authentication authentication,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(similarGameService.getBecauseYouLiked(gameId, token, limit));
    }

    // Refresh button on a "Because you liked X" section rotates the seed to a different rated
    // >= 7 game. excludeSeedIgdbIds carries all currently-shown seeds (both BYL sections) so
    // the new pick collides with neither. Returns one fresh section or 204 if no alt eligible.
    @GetMapping("/dashboard/because-you-liked")
    public ResponseEntity<BecauseYouLikedDTO> rollBecauseYouLiked(
            Authentication authentication,
            @RequestParam(required = false) java.util.List<Integer> excludeSeedIgdbIds) {
        String token = JwtUtils.getBearerToken(authentication);
        java.util.Set<Integer> excludes = excludeSeedIgdbIds == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(excludeSeedIgdbIds);
        java.util.List<BecauseYouLikedDTO> rolled = dashboardService.rollBecauseYouLiked(token, excludes, 1);
        if (rolled.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(rolled.get(0));
    }

    // POST for same reason as /personalized; body optional for cold-start callers.
    @PostMapping("/dashboard")
    public ResponseEntity<DashboardDTO> getDashboard(
            Authentication authentication,
            @Valid @RequestBody(required = false) DashboardRequest request) {
        String userId = JwtUtils.getUserId(authentication);
        String token = JwtUtils.getBearerToken(authentication);
        Set<Integer> recent = (request == null || request.getRecentlyShownIds() == null)
                ? null
                : new HashSet<>(request.getRecentlyShownIds());
        return ResponseEntity.ok(dashboardService.getDashboard(userId, token, recent));
    }
}
