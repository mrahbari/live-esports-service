package com.abioscase.live.livedata.polling;

import com.abioscase.live.config.AbiosProperties;
import com.abioscase.live.config.IngestionProperties;
import com.abioscase.live.config.PollingProperties;
import com.abioscase.live.integration.abios.AbiosResilientFetch;
import com.abioscase.live.integration.abios.model.AbiosSeriesNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Polls Abios for upcoming series every ~15 seconds.
 * Only fetches the first N pages (start-asc ordered) to track near-future series.
 * Skipped in mock mode — mock data belongs in the V1 in-memory cache, not the DB.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.polling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class UpcomingSeriesWorker {

    private final AbiosProperties abios;
    private final PollingProperties polling;
    private final IngestionProperties ingestion;
    private final AbiosResilientFetch resilient;
    private final PollingSeriesRepository repo;
    private final Semaphore pollingApiSemaphore;

    @Scheduled(fixedDelayString = "${app.polling.upcoming-interval-ms:15000}")
    public void poll() {
        Instant pollStart = Instant.now();
        try {
            int upserted = ingestFromUpstream(abios.getUpcomingPollingQuery(), polling.getMaxUpcomingPages(), pollStart);

            Duration staleness = Duration.ofMillis(polling.getUpcomingIntervalMs() * polling.getEndedThresholdCycles());
            Instant threshold = pollStart.minus(staleness);
            int ended = repo.markEndedIfStale("upcoming", threshold);

            if (upserted > 0 || ended > 0) {
                log.info("UpcomingSeriesWorker: upserted={} ended={}", upserted, ended);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("UpcomingSeriesWorker interrupted");
        } catch (Exception e) {
            log.warn("UpcomingSeriesWorker poll failed: {}", e.getMessage());
        }
    }

    private int ingestFromUpstream(String query, int maxPages, Instant pollStart) throws InterruptedException {
        int skip = 0;
        int take = abios.getBatchSize();
        int page = 0;
        int total = 0;

        while (page < maxPages) {
            pollingApiSemaphore.acquire();
            List<AbiosSeriesNode> series;
            try {
                series = resilient.fetchSeriesWithQuery(take, skip, query).allSeries();
            } finally {
                pollingApiSemaphore.release();
            }

            if (series.isEmpty()) break;

            for (AbiosSeriesNode s : series) {
                if (s.getId() == null) continue;
                Instant start = SeriesWindowFilter.parseInstant(s.getStartedAt());
                if (!SeriesWindowFilter.isWithinWindowStrict(start, pollStart, ingestion.getWindowDays())) {
                    log.info("Skipping series {} outside time window", s.getId());
                    continue;
                }
                repo.upsert(s.getId(), "upcoming", start, s.getResourceVersion(), pollStart);
                total++;
            }

            if (series.size() < take) break;
            skip += take;
            page++;
        }
        return total;
    }

}
