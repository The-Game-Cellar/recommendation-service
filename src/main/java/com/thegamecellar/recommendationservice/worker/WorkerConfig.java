package com.thegamecellar.recommendationservice.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WorkerConfig {

    // Fixed pool caps concurrent per-user compute work so a queue spike cannot starve the JVM.
    // Size 5 matches the per-tick batch cap (one slot per task) and stays well below HikariCP's
    // default 10-connection pool so DB connections do not become the bottleneck.
    @Bean(name = "perUserComputeExecutor", destroyMethod = "shutdown")
    public ExecutorService perUserComputeExecutor() {
        return Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "per-user-compute");
            t.setDaemon(true);
            return t;
        });
    }
}
