package com.medtech.vitalsmanagement.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Configure thread pool for async tasks (SSE streaming, etc.)
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core threads: number of threads to keep alive
        executor.setCorePoolSize(5);
        
        // Max threads: maximum number of threads in pool
        executor.setMaxPoolSize(20);
        
        // Queue capacity: max tasks to queue before rejecting
        executor.setQueueCapacity(100);
        
        // Thread name prefix
        executor.setThreadNamePrefix("vital-stream-");
        
        // Shutdown settings
        executor.setAwaitTerminationSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        executor.initialize();
        return executor;
    }
}
