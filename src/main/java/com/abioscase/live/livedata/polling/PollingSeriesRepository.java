package com.abioscase.live.livedata.polling;

import com.abioscase.live.livedata.web.dto.SeriesItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PollingSeriesRepository {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public void upsert(String id, String state, Instant startedAt, Integer resourceVersion, Instant now) {
        if (id == null || id.isBlank()) return;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("state", state);
        params.put("startedAt", startedAt != null ? Timestamp.from(startedAt) : null);
        params.put("resourceVersion", resourceVersion);
        params.put("now", Timestamp.from(now));

        jdbc.update("""
            INSERT INTO live_series (id, state, started_at, resource_version, updated_at)
            VALUES (:id, :state, :startedAt, :resourceVersion, :now)
            ON CONFLICT (id) DO UPDATE SET
                state            = EXCLUDED.state,
                started_at       = EXCLUDED.started_at,
                resource_version = COALESCE(EXCLUDED.resource_version, live_series.resource_version),
                updated_at       = EXCLUDED.updated_at
            """, params);
    }

    public int markEndedIfStale(String state, Instant threshold) {
        return jdbc.update("""
            UPDATE live_series SET state = 'ended', updated_at = NOW()
            WHERE state = :state AND updated_at < :threshold
            """, Map.of("state", state, "threshold", Timestamp.from(threshold)));
    }

    public PageResult getByState(String state, String cursor, int take) {
        int limit = clamp(take);
        Map<String, Object> params = new HashMap<>();
        params.put("state", state);
        params.put("limit", limit + 1);

        String sql;
        if (cursor != null && !cursor.isBlank()) {
            sql = """
                SELECT id, state, started_at, updated_at FROM live_series
                WHERE state = :state AND id > :cursor
                ORDER BY id ASC LIMIT :limit
                """;
            params.put("cursor", cursor);
        } else {
            sql = """
                SELECT id, state, started_at, updated_at FROM live_series
                WHERE state = :state
                ORDER BY id ASC LIMIT :limit
                """;
        }

        List<SeriesItem> rows = jdbc.query(sql, params, (rs, n) -> {
            Timestamp startTs   = rs.getTimestamp("started_at");
            Timestamp updatedTs = rs.getTimestamp("updated_at");
            return new SeriesItem(
                    rs.getString("id"),
                    rs.getString("state"),
                    startTs   != null ? startTs.toInstant()   : null,
                    updatedTs != null ? updatedTs.toInstant() : Instant.EPOCH
            );
        });

        boolean hasMore = rows.size() > limit;
        List<SeriesItem> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? page.get(page.size() - 1).id() : null;

        return new PageResult(page, nextCursor, hasMore);
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    public record PageResult(List<SeriesItem> items, String nextCursor, boolean hasMore) {}
}
