package com.abioscase.live.livedata.web.rest;

import com.abioscase.live.livedata.db.service.DbLiveDataService;
import com.abioscase.live.livedata.polling.PollingSeriesRepository;
import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import com.abioscase.live.livedata.web.dto.SeriesRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Series Polling (V2)", description = "Database-backed scalable endpoints — cursor paginated, powered by PostgreSQL")
@RestController
@RequestMapping("/v2")
@RequiredArgsConstructor
public class SeriesPollingController {

    private final PollingSeriesRepository pollingRepo;
    private final DbLiveDataService dbService;

    @Operation(
        summary = "Live series (V2 - Enriched)",
        description = "Returns enriched live series from the database-backed ingestion pipeline."
    )
    @GetMapping("/series/live")
    public V2Page<LiveSeriesItem> getLiveSeries(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int take) {
        DbLiveDataService.PageResult<LiveSeriesItem> result = dbService.getLiveSeries(cursor, take);
        return new V2Page<>(result.items(), result.nextCursor(), result.items().size(), result.nextCursor() != null);
    }

    @Operation(
        summary = "Upcoming series (V2 - Thin)",
        description = "Returns near-future upcoming series IDs from the polling pipeline. Filtered by window-days."
    )
    @GetMapping("/series/upcoming")
    public V2Page<SeriesRow> getUpcomingSeries(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int take) {
        PollingSeriesRepository.PageResult result = pollingRepo.getByState("upcoming", cursor, take);
        return new V2Page<>(result.items(), result.nextCursor(), result.items().size(), result.hasMore());
    }

    @Operation(
        summary = "Live teams (V2 - Enriched)",
        description = "Returns enriched live teams participating in ongoing series."
    )
    @GetMapping("/teams/live")
    public V2Page<LiveTeamItem> getLiveTeams(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int take) {
        DbLiveDataService.PageResult<LiveTeamItem> result = dbService.getLiveTeams(cursor, take);
        return new V2Page<>(result.items(), result.nextCursor(), result.items().size(), result.nextCursor() != null);
    }

    @Operation(
        summary = "Live players (V2 - Enriched)",
        description = "Returns enriched live players participating in ongoing series."
    )
    @GetMapping("/players/live")
    public V2Page<LivePlayerItem> getLivePlayers(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int take) {
        DbLiveDataService.PageResult<LivePlayerItem> result = dbService.getLivePlayers(cursor, take);
        return new V2Page<>(result.items(), result.nextCursor(), result.items().size(), result.nextCursor() != null);
    }

    public record V2Page<T>(List<T> items, String nextCursor, int count, boolean hasMore) {}
}
