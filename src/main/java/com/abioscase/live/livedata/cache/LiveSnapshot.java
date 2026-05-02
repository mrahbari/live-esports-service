package com.abioscase.live.livedata.cache;

import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LiveSnapshot(
        Instant fetchedAt,
        boolean stale,
        boolean degraded,
        String degradeMessage,
        List<LiveSeriesItem> series,
        List<LivePlayerItem> players,
        List<LiveTeamItem> teams,
        Map<String, Integer> atlasCalls) {

    public LiveSnapshot asStale() {
        if (stale) {
            return this;
        }
        return new LiveSnapshot(fetchedAt, true, degraded, degradeMessage, series, players, teams, atlasCalls);
    }

    public LiveSnapshot asFresh() {
        if (!stale) {
            return this;
        }
        return new LiveSnapshot(fetchedAt, false, degraded, degradeMessage, series, players, teams, atlasCalls);
    }

    public LiveSnapshot withDegraded(String message) {
        return new LiveSnapshot(fetchedAt, true, true, message, series, players, teams, atlasCalls);
    }
}
