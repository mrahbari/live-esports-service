package com.abioscase.live.livedata.web.rest;

import com.abioscase.live.livedata.web.dto.LiveListResponse;
import com.abioscase.live.livedata.web.dto.LiveMeta;
import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import com.abioscase.live.livedata.application.LiveDataService;
import com.abioscase.live.livedata.cache.LiveSnapshot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LiveDataController {

    private final LiveDataService liveDataService;

    @GetMapping({"/v1/series/live", "/series/live", "/v1/series"})
    public LiveListResponse<LiveSeriesItem> liveSeries(
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false, defaultValue = "0") int skip,
            @RequestParam(required = false, defaultValue = "0") int take) {
        LiveSnapshot d = resolve(forceRefresh);
        return wrap(d, d.series(), skip, take);
    }

    @GetMapping({"/v1/players/live", "/players/live", "/v1/players"})
    public LiveListResponse<LivePlayerItem> livePlayers(
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false, defaultValue = "0") int skip,
            @RequestParam(required = false, defaultValue = "0") int take) {
        LiveSnapshot d = resolve(forceRefresh);
        // When degraded (serving very old stale data), drop the heavy players payload to save bandwidth.
        List<LivePlayerItem> players = d.degraded() ? List.of() : d.players();
        return wrap(d, players, skip, take);
    }

    @GetMapping({"/v1/teams/live", "/teams/live", "/v1/teams"})
    public LiveListResponse<LiveTeamItem> liveTeams(
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false, defaultValue = "0") int skip,
            @RequestParam(required = false, defaultValue = "0") int take) {
        LiveSnapshot d = resolve(forceRefresh);
        return wrap(d, d.teams(), skip, take);
    }

    private LiveSnapshot resolve(boolean forceRefresh) {
        return forceRefresh ? liveDataService.forceRefresh() : liveDataService.getSnapshot();
    }

    private static <T> LiveListResponse<T> wrap(LiveSnapshot d, List<T> allItems, int skip, int take) {
        int total = allItems.size();
        int effectiveTake = (take > 0) ? take : total;
        int from = Math.min(skip, total);
        int to = Math.min(from + effectiveTake, total);
        List<T> page = allItems.subList(from, to);
        LiveMeta meta = LiveMeta.builder()
                .fetchedAt(d.fetchedAt().toString())
                .stale(d.stale())
                .degraded(d.degraded())
                .count(page.size())
                .total(total)
                .skip(skip)
                .take(effectiveTake)
                .hasMore(to < total)
                .atlasCalls(d.atlasCalls())
                .build();
        return new LiveListResponse<>(meta, page);
    }
}
