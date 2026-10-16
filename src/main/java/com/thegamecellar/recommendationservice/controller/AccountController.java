package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.model.dto.AccountExportDTO;
import com.thegamecellar.recommendationservice.service.AccountExportService;
import com.thegamecellar.recommendationservice.service.AccountPurgeService;
import com.thegamecellar.recommendationservice.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GDPR endpoints, called by the gateway's account delete and export. userId always comes from JWT.
@RestController
@RequestMapping("/api/v1/recommendations/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountPurgeService accountPurgeService;
    private final AccountExportService accountExportService;

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountExportDTO> exportAccount(Authentication authentication) {
        String userId = JwtUtils.getUserId(authentication);
        return ResponseEntity.ok(accountExportService.exportUser(userId));
    }

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
