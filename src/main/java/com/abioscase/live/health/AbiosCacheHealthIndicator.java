package com.abioscase.live.health;

import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.livedata.cache.LiveSnapshotCache;
import com.abioscase.live.config.LiveDataProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("abiosCache")
public class AbiosCacheHealthIndicator implements HealthIndicator {
    private final LiveSnapshotCache snapshotCache;
    private final LiveDataProperties liveProps;

    public AbiosCacheHealthIndicator(LiveSnapshotCache snapshotCache, LiveDataProperties liveProps) {
        this.snapshotCache = snapshotCache;
        this.liveProps = liveProps;
    }

    @Override
    public Health health() {
        Optional<LiveSnapshot> snapshot = snapshotCache.get();
        if (snapshot.isEmpty()) {
            return Health.down()
                    .withDetail("reason", "No snapshot available yet")
                    .withDetail("hasSnapshot", false)
                    .build();
        }

        LiveSnapshot s = snapshot.get();
        long ageSeconds = Math.max(0, Duration.between(s.fetchedAt(), Instant.now()).getSeconds());
        boolean withinMaxStale = ageSeconds <= liveProps.getMaxStaleTtlSeconds();
        if (!withinMaxStale) {
            return Health.down()
                    .withDetail("reason", "Snapshot exceeded max stale TTL")
                    .withDetail("hasSnapshot", true)
                    .withDetail("fetchedAt", s.fetchedAt().toString())
                    .withDetail("ageSeconds", ageSeconds)
                    .withDetail("maxStaleTtlSeconds", liveProps.getMaxStaleTtlSeconds())
                    .build();
        }

        return Health.up()
                .withDetail("hasSnapshot", true)
                .withDetail("fetchedAt", s.fetchedAt().toString())
                .withDetail("ageSeconds", ageSeconds)
                .withDetail("stale", s.stale())
                .withDetail("degraded", s.degraded())
                .build();
    }
}
