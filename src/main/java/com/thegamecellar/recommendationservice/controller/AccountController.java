package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.service.AccountPurgeService;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GDPR endpoint, called by the gateway's account delete. userId always comes from JWT.
@RestController
@RequestMapping("/api/v1/recommendations/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountPurgeService accountPurgeService;

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteAccount(Authentication authentication) {
        String userId = JwtUtils.getUserId(authentication);
        AccountPurgeService.PurgeResult result = accountPurgeService.purgeUser(userId);
        return ResponseEntity.ok(Map.of(
                "message", "Recommendation data purged",
                "queueRemoved", result.queueRemoved(),
                "holdingRemoved", result.holdingRemoved(),
                "poolRemoved", result.poolRemoved(),
                "profileRemoved", result.profileRemoved()
        ));
    }
}
