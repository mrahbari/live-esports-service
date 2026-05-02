package com.abioscase.live.livedata.application;

import com.abioscase.live.integration.abios.exception.AbiosUpstreamTransientException;
import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.livedata.cache.LiveSnapshotCache;
import com.abioscase.live.config.LiveDataProperties;
import com.abioscase.live.integration.abios.AbiosSnapshotReader;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Caches the aggregated snapshot with TTL. A single refresh path bounds upstream QPS.
 * Thread-safe: single-writer via lock, all request threads read a volatile AtomicReference.
 */
@Slf4j
@Service
public class LiveDataService {

    private static final String REFRESH_LOCK_KEY = "lock:live-refresh";

    private final AbiosSnapshotReader snapshotReader;
    private final LiveSnapshotAggregator aggregator;
    private final LiveSnapshotCache snapshotCache;
    private final LiveDataProperties liveProps;
    private final MeterRegistry registry;
    private final Optional<StringRedisTemplate> redisTemplate;
    private final Executor refreshExecutor;

    public LiveDataService(
            AbiosSnapshotReader snapshotReader,
            LiveSnapshotAggregator aggregator,
            LiveSnapshotCache snapshotCache,
            LiveDataProperties liveProps,
            MeterRegistry registry,
            Optional<StringRedisTemplate> redisTemplate,
            @Qualifier("cacheRefreshExecutor") Executor refreshExecutor) {
        this.snapshotReader = snapshotReader;
        this.aggregator = aggregator;
        this.snapshotCache = snapshotCache;
        this.liveProps = liveProps;
        this.registry = registry;
        this.redisTemplate = redisTemplate;
        this.refreshExecutor = refreshExecutor;
    }

    private final Object refreshLock = new Object();
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    public LiveSnapshot getSnapshot() {
        LiveSnapshot snap = snapshotCache.get().orElse(null);
        if (snap == null) {
            org.slf4j.MDC.put("cacheStatus", "MISS");
            registry.counter("cache.miss").increment();
            LiveSnapshot loaded = refreshBlocking("request-initial");
            if (loaded == null) {
                throw new AbiosUpstreamTransientException("No live snapshot available — service is warming up");
            }
            return loaded;
        }

        if (isFresh(snap)) {
            org.slf4j.MDC.put("cacheStatus", "HIT");
            registry.counter("cache.hit").increment();
            return snap.asFresh();
        }

        org.slf4j.MDC.put("cacheStatus", "STALE");
        registry.counter("cache.stale_served").increment();
        if (isWithinStaleTtl(snap)) {
            triggerAsyncRefresh("request-stale");
            return snap.asStale();
        }
        return refreshBlocking("request-expired");
    }

    /** Forces a synchronous upstream refresh, bypassing the TTL check. Rate-limited by the caller. */
    public LiveSnapshot forceRefresh() {
        synchronized (refreshLock) {
            return loadWithFallback("force-refresh", snapshotCache.get().orElse(null));
        }
    }

    public void refreshInBackground() {
        if (!liveProps.isBackgroundRefreshEnabled()) {
            return;
        }
        LiveSnapshot snap = snapshotCache.get().orElse(null);
        if (snap != null && isFresh(snap)) {
            return;
        }
        triggerAsyncRefresh("scheduled");
    }

    private boolean isFresh(LiveSnapshot s) {
        long ttl = liveProps.getCacheTtlSeconds();
        // Adaptive TTL: extend slightly when no live series are in progress
        if (s.series().isEmpty()) {
            ttl = ttl * 3 / 2;
        }
        return s.fetchedAt()
                .plus(ttl, ChronoUnit.SECONDS)
                .isAfter(Instant.now());
    }

    private LiveSnapshot refreshBlocking(String source) {
        synchronized (refreshLock) {
            LiveSnapshot snap = snapshotCache.get().orElse(null);
            if (snap != null && isFresh(snap)) {
                return snap.asFresh();
            }

            if ("redis".equalsIgnoreCase(liveProps.getCacheMode()) && redisTemplate.isPresent()) {
                Boolean acquired = redisTemplate.get().opsForValue()
                        .setIfAbsent(REFRESH_LOCK_KEY, "locked", Duration.ofSeconds(30));
                if (Boolean.FALSE.equals(acquired)) {
                    log.debug("Distributed refresh lock busy, skipping refresh ({})", source);
                    return snap != null ? snap.asStale() : null;
                }
                try {
                    return loadWithFallback(source, snap);
                } finally {
                    redisTemplate.get().delete(REFRESH_LOCK_KEY);
                }
            }
            return loadWithFallback(source, snap);
        }
    }

    private LiveSnapshot loadWithFallback(String source, LiveSnapshot previous) {
        long start = System.currentTimeMillis();
        try {
            AbiosEnrichedDocument raw = snapshotReader.loadRawSeries();
            LiveSnapshot up = aggregator.aggregate(raw);
            Objects.requireNonNull(up, "aggregator returned null snapshot");
            snapshotCache.put(up);
            registry.counter("upstream.success").increment();
            registry.timer("upstream.request.latency").record(Duration.ofMillis(System.currentTimeMillis() - start));
            org.slf4j.MDC.put("upstreamStatus", "SUCCESS");
            log.debug("Live snapshot loaded ({}) series={}", source, up.series().size());
            return up;
        } catch (Exception e) {
            registry.counter("upstream.error").increment();
            org.slf4j.MDC.put("upstreamStatus", "ERROR");
            log.warn("Live snapshot load failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            if (previous != null && isWithinStaleTtl(previous)) {
                return previous.withDegraded("Serving stale data: " + e.getMessage());
            }
            throw e;
        }
    }

    private boolean isWithinStaleTtl(LiveSnapshot s) {
        return s.fetchedAt()
                .plus(liveProps.getMaxStaleTtlSeconds(), ChronoUnit.SECONDS)
                .isAfter(Instant.now());
    }

    private void triggerAsyncRefresh(String source) {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refreshBlocking(source);
            } catch (Exception e) {
                log.debug("Async refresh failed ({}): {}", source, e.getMessage());
            } finally {
                refreshInProgress.set(false);
            }
        });
    }
}
