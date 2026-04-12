package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.model.dto.RecommendationDTO;
import com.thegamecellar.recommendationservice.service.RecommendationService;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/personalized")
    public ResponseEntity<List<RecommendationDTO>> getPersonalized(
            Authentication authentication,
            @RequestParam(defaultValue = "10") int limit) {
        String token = JwtUtils.getBearerToken(authentication);
        return ResponseEntity.ok(recommendationService.getPersonalized(token, limit));
    }
}
