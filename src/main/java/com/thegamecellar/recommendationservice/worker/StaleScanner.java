package com.thegamecellar.recommendationservice.worker;

import com.thegamecellar.recommendationservice.repository.UserCandidatePoolRepository;
import com.thegamecellar.recommendationservice.service.ComputeEnqueuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

// Hourly safety-net: any user with a pool whose newest row's computed_at is older than the
// stale-ttl threshold gets re-enqueued. Catches users who haven't loaded the dashboard so
// the request-path stale-check never fired, and library-write events that got dropped on a
// Redis outage. Worker dedupes via compute_queue upsert if the user is already queued.
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleScanner {

    private final UserCandidatePoolRepository poolRepository;
    private final ComputeEnqueuer computeEnqueuer;

    @Value("${recommendation.stale-ttl-hours:24}")
    private long staleTtlHours;

    @Scheduled(fixedDelayString = "${recommendation.stale-scan.fixed-delay-ms:3600000}",
            initialDelayString = "${recommendation.stale-scan.initial-delay-ms:300000}")
    public void scan() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(staleTtlHours);
        List<String> staleUserIds = poolRepository.findStaleUserIds(cutoff);
        if (staleUserIds.isEmpty()) return;
        for (String userId : staleUserIds) {
            computeEnqueuer.enqueue(userId);
        }
        log.info("StaleScanner enqueued {} users with pool older than {}h", staleUserIds.size(), staleTtlHours);
    }
}
