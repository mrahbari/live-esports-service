package com.abioscase.live.livedata.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.live.cache-mode", havingValue = "redis")
@RequiredArgsConstructor
public class RedisLiveSnapshotCache implements LiveSnapshotCache {

    private static final String CACHE_KEY = "live:snapshot";
    private static final long L1_TTL_MS = 2000; // 2 seconds L1 cache

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicReference<L1Entry> l1Cache = new AtomicReference<>();

    @Override
    public Optional<LiveSnapshot> get() {
        L1Entry entry = l1Cache.get();
        if (entry != null && (System.currentTimeMillis() - entry.timestamp) < L1_TTL_MS) {
            return Optional.of(entry.snapshot);
        }

        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            if (json == null) {
                l1Cache.set(null);
                return Optional.empty();
            }
            LiveSnapshot snapshot = objectMapper.readValue(json, LiveSnapshot.class);
            l1Cache.set(new L1Entry(snapshot, System.currentTimeMillis()));
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.error("Failed to read from Redis cache", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(LiveSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(CACHE_KEY, json, 1, TimeUnit.HOURS);
            l1Cache.set(new L1Entry(snapshot, System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("Failed to write to Redis cache", e);
        }
    }

    private record L1Entry(LiveSnapshot snapshot, long timestamp) {}
}
