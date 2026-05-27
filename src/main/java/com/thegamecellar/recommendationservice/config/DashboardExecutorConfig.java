package com.thegamecellar.recommendationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DashboardExecutorConfig {

    // Pool size 6: 2 slots per sub-payload (recs, wildcard, becauseYouLiked) so the dashboard
    // composite never queues. I/O-bound work (HTTP + JDBC) so a fixed pool is fine. Daemon threads
    // so JVM shutdown is not blocked by an idle pool.
    @Bean(name = "dashboardExecutor", destroyMethod = "shutdown")
    public ExecutorService dashboardExecutor() {
        return Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "dashboard-sub");
            t.setDaemon(true);
            return t;
        });
    }
}
