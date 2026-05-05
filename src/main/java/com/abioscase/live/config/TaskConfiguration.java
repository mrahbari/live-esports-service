package com.abioscase.live.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Central wiring for asynchronous work, scheduled polling, and outbound API concurrency.
 *
 * Background execution: exposes {@code cacheRefreshExecutor}, a bounded thread pool used for async cache
 * refresh and similar fire-and-forget work.
 *
 * Scheduling: exposes {@code taskScheduler}, a dedicated pool for polling workers (e.g. live/upcoming series)
 * so periodic tasks do not compete with ad-hoc async jobs.
 *
 * Upstream pressure control: exposes a fair {@link Semaphore} shared across polling workers to cap concurrent
 * outbound Abios calls according to {@link PollingProperties}.
 */
@Configuration
public class TaskConfiguration {

    @Bean(name = "cacheRefreshExecutor")
    public Executor cacheRefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 2);
        executor.setMaxPoolSize(processors * 4);
        executor.setQueueCapacity(50);  // If all threads are busy, up to 50 tasks will remain in the queue.
        executor.setThreadNamePrefix("cache-refresh-");
        executor.initialize();
        return executor;
    }

    /** Dedicated scheduler for polling workers — needs at least 2 threads for Live + Upcoming to run in parallel. */
    @Bean(name = "taskScheduler")
    public TaskScheduler pollingTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("polling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    /** Shared semaphore that caps concurrent outbound Abios API calls across all polling workers. */
    @Bean
    public Semaphore pollingApiSemaphore(PollingProperties polling) {
        return new Semaphore(polling.getMaxConcurrentCalls(), true);
    }
}
