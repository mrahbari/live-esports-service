package com.abioscase.live.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class TaskConfiguration {

    @Bean(name = "cacheRefreshExecutor")
    public Executor cacheRefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Sized for I/O bound tasks (waiting for Abios)
        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 2);
        executor.setMaxPoolSize(processors * 4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cache-refresh-");
        executor.initialize();
        return executor;
    }
}
