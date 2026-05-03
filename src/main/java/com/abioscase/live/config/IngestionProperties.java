package com.abioscase.live.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ingestion.series")
public class IngestionProperties {
    /**
     * The time-window in days to limit the dataset.
     * Series with start_time outside [now - window_days, now + window_days] are ignored.
     */
    @Min(0)
    private int windowDays = 25;
}
