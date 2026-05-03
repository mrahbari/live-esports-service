package com.abioscase.live.livedata.polling;

import com.abioscase.live.config.PollingProperties;
import com.abioscase.live.livedata.db.ingestion.StreamingIngestionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls Abios for live series every ~3 seconds.
 * Triggers the full database-backed ingestion pipeline (enriched series, teams, players).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.polling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LiveSeriesWorker {

    private final PollingProperties polling;
    private final StreamingIngestionPipeline pipeline;

    @Scheduled(fixedDelayString = "${app.polling.live-interval-ms:3000}")
    public void poll() {
        try {
            pipeline.runIngestion();
        } catch (Exception e) {
            log.warn("LiveSeriesWorker poll failed: {}", e.getMessage());
        }
    }
}
