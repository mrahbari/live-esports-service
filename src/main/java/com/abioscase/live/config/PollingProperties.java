package com.abioscase.live.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "app.polling")
public class PollingProperties {

    private boolean enabled = true;

    @Positive
    private long liveIntervalMs = 3_000;

    @Positive
    private long upcomingIntervalMs = 15_000;

    @Positive
    private int maxUpcomingPages = 5;

    @Min(1)
    private int endedThresholdCycles = 3;

    @Min(1)
    @Max(10)
    private int maxConcurrentCalls = 4;
}
