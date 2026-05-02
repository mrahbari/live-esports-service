package com.abioscase.live.livedata.cache;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.live.cache-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryLiveSnapshotCache implements LiveSnapshotCache {
    private final AtomicReference<LiveSnapshot> ref = new AtomicReference<>();

    @Override
    public Optional<LiveSnapshot> get() {
        return Optional.ofNullable(ref.get());
    }

    @Override
    public void put(LiveSnapshot snapshot) {
        ref.set(snapshot);
    }
}
