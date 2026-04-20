package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.model.dto.DashboardDTO;
import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.service.DashboardService;
import com.thegamecellar.recommendationservice.service.RecommendationService;
import com.thegamecellar.recommendationservice.service.SimilarGameService;
import com.thegamecellar.recommendationservice.service.WildCardService;
import com.thegamecellar.recommendationservice.util.JwtUtils;
// TODO (post-MVP): Implement rate limiting to prevent abuse. Each request can trigger multiple
//  downstream calls to Game Service and Library Service. Rate limiting should be handled
//  centrally in API Gateway (port 8000) rather than per-service.
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final WildCardService wildCardService;
    private final SimilarGameService similarGameService;
    private final DashboardService dashboardService;

    @GetMapping("/personalized")
    public ResponseEntity<List<RecommendationDTO>> getPersonalized(
            Authentication authentication,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(recommendationService.getPersonalized(token, limit));
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

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardDTO> getDashboard(Authentication authentication) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(dashboardService.getDashboard(token));
    }
}
