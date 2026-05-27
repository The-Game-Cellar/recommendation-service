package com.thegamecellar.recommendationservice.controller;

import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/admin/rec")
@RequiredArgsConstructor
public class AdminRecController {

    private final ComputeQueueRepository computeQueueRepository;

    @PostMapping("/enqueue")
    @Transactional
    public ResponseEntity<Map<String, Object>> enqueue(@NotBlank @RequestParam String userId) {
        computeQueueRepository.upsert(userId, LocalDateTime.now());
        return ResponseEntity.accepted().body(Map.of("userId", userId, "queuedAt", LocalDateTime.now()));
    }
}
