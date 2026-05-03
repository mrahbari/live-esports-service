package com.abioscase.live.livedata.db.service;

import com.abioscase.live.livedata.db.ingestion.StreamingIngestionPipeline;
import com.abioscase.live.livedata.db.repository.JdbcLiveRepository;
import com.abioscase.live.livedata.web.dto.LivePlayerItem;
import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import com.abioscase.live.livedata.web.dto.LiveTeamItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DbLiveDataService {

    private static final Set<String> LIVE_STATES = Set.of("live", "ongoing");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcLiveRepository repository;
    private final StreamingIngestionPipeline pipeline;

    public void triggerIngestion() {
        pipeline.runIngestion();
    }

    public PageResult<LiveSeriesItem> getLiveSeries(String cursor, int limit) {
        int effectiveLimit = clamp(limit);
        List<LiveSeriesItem> rows = repository.findLiveSeries(cursor, LIVE_STATES, effectiveLimit + 1);

        boolean hasMore = rows.size() > effectiveLimit;
        List<LiveSeriesItem> page = hasMore ? rows.subList(0, effectiveLimit) : rows;
        String nextCursor = hasMore ? page.get(page.size() - 1).seriesId() : null;

        long total = repository.countEnrichedSeriesByState(LIVE_STATES);
        return new PageResult<>(page, nextCursor, total);
    }

    public PageResult<LiveTeamItem> getLiveTeams(String cursor, int limit) {
        int effectiveLimit = clamp(limit);
        List<JdbcLiveRepository.TeamRow> teamRows = repository.findLiveTeams(cursor, LIVE_STATES, effectiveLimit + 1);

        boolean hasMore = teamRows.size() > effectiveLimit;
        List<JdbcLiveRepository.TeamRow> page = hasMore ? teamRows.subList(0, effectiveLimit) : teamRows;
        String nextCursor = hasMore ? page.get(page.size() - 1).id() : null;

        // series_ids is already on each TeamRow — no extra query needed
        List<LiveTeamItem> items = page.stream()
                .map(t -> new LiveTeamItem(t.id(), t.name(), t.abbreviation(), t.playerCount(), t.seriesIds()))
                .toList();

        long total = repository.countLiveTeamsByState(LIVE_STATES);
        return new PageResult<>(items, nextCursor, total);
    }

    public PageResult<LivePlayerItem> getLivePlayers(String cursor, int limit) {
        int effectiveLimit = clamp(limit);
        List<JdbcLiveRepository.PlayerRow> playerRows = repository.findLivePlayers(cursor, LIVE_STATES, effectiveLimit + 1);

        boolean hasMore = playerRows.size() > effectiveLimit;
        List<JdbcLiveRepository.PlayerRow> page = hasMore ? playerRows.subList(0, effectiveLimit) : playerRows;
        String nextCursor = hasMore ? page.get(page.size() - 1).id() : null;

        // series_ids is already on each PlayerRow — no extra query needed
        List<LivePlayerItem> items = page.stream()
                .map(p -> new LivePlayerItem(p.id(), p.nickname(), p.firstName(), p.lastName(),
                        p.role(), p.teamId(), p.teamName(), p.seriesIds()))
                .toList();

        long total = repository.countLivePlayersByState(LIVE_STATES);
        return new PageResult<>(items, nextCursor, total);
    }

    private int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    public record PageResult<T>(List<T> items, String nextCursor, long total) {}
}
