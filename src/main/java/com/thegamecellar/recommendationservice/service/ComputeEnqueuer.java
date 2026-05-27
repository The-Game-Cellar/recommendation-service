package com.thegamecellar.recommendationservice.service;

import com.thegamecellar.recommendationservice.repository.ComputeQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// Tiny wrapper so @Modifying upsert runs in its own short-lived transaction. Called from
// RecommendationService read path which is otherwise tx-less; without this Spring rejects
// the modifying query with InvalidDataAccessApiUsageException.
@Slf4j
@Service
@RequiredArgsConstructor
public class ComputeEnqueuer {

    private final ComputeQueueRepository queueRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(String userId) {
        try {
            queueRepository.upsert(userId, LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("Failed to enqueue {} for compute: {}", userId, ex.getClass().getSimpleName());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueGenre(String userId, String genre) {
        try {
            queueRepository.upsertGenre(userId, LocalDateTime.now(), genre);
        } catch (RuntimeException ex) {
            log.warn("Failed to enqueue {}/{} for per-genre compute: {}", userId, genre, ex.getClass().getSimpleName());
        }
    }
}
