package com.abioscase.live.livedata.cache;

import java.util.Optional;

public interface LiveSnapshotCache {
    Optional<LiveSnapshot> get();

    void put(LiveSnapshot snapshot);
}
