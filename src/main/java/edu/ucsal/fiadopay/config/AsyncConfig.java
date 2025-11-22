package edu.ucsal.fiadopay.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService fiadoExecutor() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("fiado-exec-" + System.nanoTime());
            t.setDaemon(false);
            return t;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService fiadoScheduler() {
        int threads = 2;
        return Executors.newScheduledThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("fiado-sched-" + System.nanoTime());
            t.setDaemon(false);
            return t;
        });
    }
}
