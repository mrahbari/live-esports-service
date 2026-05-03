package com.abioscase.live.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "app.live")
public class LiveDataProperties {
    private String cacheMode = "memory"; // "memory" or "redis"
    @Positive
    private long cacheTtlSeconds = 20;
    @Positive
    private long backgroundRefreshMs = 15_000L;
    @Positive
    private long backgroundRefreshInitialDelayMs = 5_000L;
    private boolean backgroundRefreshEnabled = true;
    /** Stale-while-revalidate: still serve last snapshot for this long when upstream failed */
    @Positive
    private long maxStaleTtlSeconds = 300;
    /** Per-IP inbound request budget per minute (token bucket filter). */
    @Positive
    private int clientRateLimitPerMinute = 120;
    /** Token bucket burst size for each IP. */
    @Positive
    private int clientBurstCapacity = 120;
}
