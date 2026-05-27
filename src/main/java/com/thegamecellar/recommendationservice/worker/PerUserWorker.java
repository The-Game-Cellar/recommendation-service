package com.thegamecellar.recommendationservice.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Scheduled batch driver. Each tick submits BATCH_SIZE parallel tasks to the fixed thread pool.
// Each task calls UserComputeProcessor.processOneAtomicallyOrSkip which runs in its own
// transaction with SELECT FOR UPDATE SKIP LOCKED, so concurrent threads safely pick different
// queue rows. Tasks that find an empty queue return false fast.
@Slf4j
@Component
public class PerUserWorker {

    public static final int BATCH_SIZE = 5;
    public static final long TASK_TIMEOUT_SECONDS = 60;

    private final UserComputeProcessor processor;
    private final ExecutorService executor;

    public PerUserWorker(UserComputeProcessor processor,
                         @Qualifier("perUserComputeExecutor") ExecutorService executor) {
        this.processor = processor;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${recommendation.worker.fixed-delay-ms:30000}",
            initialDelayString = "${recommendation.worker.initial-delay-ms:30000}")
    public void runBatch() {
        List<Future<Boolean>> futures = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            futures.add(executor.submit(processor::processOneAtomicallyOrSkip));
        }
        int processed = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS))) {
                    processed++;
                }
            } catch (TimeoutException ex) {
                log.warn("Per-user compute task timed out after {}s", TASK_TIMEOUT_SECONDS);
                f.cancel(true);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Per-user compute task failed unexpectedly: {}", ex.toString());
            }
        }
        if (processed > 0) {
            log.info("PerUserWorker tick processed {} of {} slots", processed, BATCH_SIZE);
        }
    }
}
