package com.abioscase.live.livedata.db.repository;

import com.abioscase.live.livedata.web.dto.LiveSeriesItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * All database operations for the V2 (database-backed) path.
 * Schema: live_series, live_teams (series_ids[]), live_players (series_ids[]).
 */
@Repository
@RequiredArgsConstructor
public class JdbcLiveRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // ── UPSERTS ─────────────────────────────────────────────────────────────────

    public void upsertEnrichedSeries(String id, String name, String gameName, String state,
                                     String startedAt, Integer tier, Integer bestOf, int teamCount,
                                     Integer resourceVersion, Instant updatedAt) {
        if (id == null || id.isBlank()) return;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("name", blankToNull(name));
        params.put("gameName", blankToNull(gameName));
        params.put("state", blankToNull(state));
        params.put("startedAt", parseTimestamp(startedAt));
        params.put("tier", tier);
        params.put("bestOf", bestOf);
        params.put("teamCount", teamCount);
        params.put("resourceVersion", resourceVersion);
        params.put("updatedAt", safeTimestamp(updatedAt));
        jdbc.update("""
            INSERT INTO live_series (id, name, game_name, state, started_at, tier, best_of, team_count, resource_version, updated_at)
            VALUES (:id, :name, :gameName, :state, :startedAt, :tier, :bestOf, :teamCount, :resourceVersion, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                name             = EXCLUDED.name,
                game_name        = EXCLUDED.game_name,
                state            = EXCLUDED.state,
                started_at       = EXCLUDED.started_at,
                tier             = EXCLUDED.tier,
                best_of          = EXCLUDED.best_of,
                team_count       = EXCLUDED.team_count,
                resource_version = COALESCE(EXCLUDED.resource_version, live_series.resource_version),
                updated_at       = EXCLUDED.updated_at
            """, params);
    }

    public void upsertEnrichedTeam(String id, String name, String abbreviation,
                                   int playerCount, Set<String> seriesIds, Instant updatedAt) {
        if (id == null || id.isBlank()) return;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("name", name != null ? name : "");
        params.put("abbreviation", abbreviation != null ? abbreviation : "");
        params.put("playerCount", playerCount);
        params.put("seriesIds", toSqlArray(seriesIds));
        params.put("updatedAt", safeTimestamp(updatedAt));
        jdbc.update("""
            INSERT INTO live_teams (id, name, abbreviation, player_count, series_ids, updated_at)
            VALUES (:id, :name, :abbreviation, :playerCount, :seriesIds, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                name         = EXCLUDED.name,
                abbreviation = EXCLUDED.abbreviation,
                player_count = GREATEST(EXCLUDED.player_count, live_teams.player_count),
                series_ids   = ARRAY(SELECT DISTINCT unnest(live_teams.series_ids || EXCLUDED.series_ids)),
                updated_at   = EXCLUDED.updated_at
            """, params);
    }

    public void upsertEnrichedPlayer(String id, String nickname, String firstName, String lastName,
                                     String role, String teamId, String teamName,
                                     Set<String> seriesIds, Instant updatedAt) {
        if (id == null || id.isBlank()) return;
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("nickname", blankToNull(nickname));
        params.put("firstName", blankToNull(firstName));
        params.put("lastName", blankToNull(lastName));
        params.put("role", blankToNull(role));
        params.put("teamId", blankToNull(teamId));
        params.put("teamName", blankToNull(teamName));
        params.put("seriesIds", toSqlArray(seriesIds));
        params.put("updatedAt", safeTimestamp(updatedAt));
        jdbc.update("""
            INSERT INTO live_players (id, nickname, first_name, last_name, role, team_id, team_name, series_ids, updated_at)
            VALUES (:id, :nickname, :firstName, :lastName, :role, :teamId, :teamName, :seriesIds, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                nickname   = EXCLUDED.nickname,
                first_name = EXCLUDED.first_name,
                last_name  = EXCLUDED.last_name,
                role       = EXCLUDED.role,
                team_id    = COALESCE(EXCLUDED.team_id, live_players.team_id),
                team_name  = COALESCE(EXCLUDED.team_name, live_players.team_name),
                series_ids = ARRAY(SELECT DISTINCT unnest(live_players.series_ids || EXCLUDED.series_ids)),
                updated_at = EXCLUDED.updated_at
            """, params);
    }

    public int pruneStaleEnriched(Instant cutoff) {
        Timestamp ts = safeTimestamp(cutoff);
        // Remove stale series IDs from arrays before deleting series rows
        jdbc.update("""
            UPDATE live_teams SET series_ids = ARRAY(
                SELECT s FROM unnest(series_ids) s
                WHERE EXISTS (SELECT 1 FROM live_series WHERE id = s AND updated_at >= :cutoff)
            ) WHERE series_ids != '{}'
            """, Map.of("cutoff", ts));
        jdbc.update("""
            UPDATE live_players SET series_ids = ARRAY(
                SELECT s FROM unnest(series_ids) s
                WHERE EXISTS (SELECT 1 FROM live_series WHERE id = s AND updated_at >= :cutoff)
            ) WHERE series_ids != '{}'
            """, Map.of("cutoff", ts));
        return jdbc.update("DELETE FROM live_series WHERE updated_at < :cutoff AND state != 'upcoming'",
                Map.of("cutoff", ts));
    }

    // ── QUERIES ──────────────────────────────────────────────────────────────────

    /**
     * Fetches resource_versions for a specific set of IDs.
     * Used for batch delta-checks during ingestion to avoid OOM with large datasets.
     */
    public Map<String, Integer> findResourceVersions(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> map = new HashMap<>();
        jdbc.query("SELECT id, resource_version FROM live_series WHERE id IN (:ids)",
                Map.of("ids", ids),
                rs -> {
                    map.put(rs.getString("id"), rs.getObject("resource_version", Integer.class));
                });
        return map;
    }

    public List<LiveSeriesItem> findLiveSeries(String cursor, Set<String> states, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("states", states.toArray(new String[0]));

        String sql;
        if (cursor != null && !cursor.isBlank()) {
            sql = """
                SELECT id, name, game_name, state, started_at, tier, best_of, team_count
                FROM live_series WHERE state = ANY(:states) AND id > :cursor
                ORDER BY id ASC LIMIT :limit
                """;
            params.put("cursor", cursor);
        } else {
            sql = """
                SELECT id, name, game_name, state, started_at, tier, best_of, team_count
                FROM live_series WHERE state = ANY(:states)
                ORDER BY id ASC LIMIT :limit
                """;
        }

        return jdbc.query(sql, params, (rs, n) -> new LiveSeriesItem(
                rs.getString("id"), rs.getString("name"), rs.getString("game_name"),
                rs.getString("state"), rs.getString("started_at"),
                rs.getObject("tier", Integer.class), rs.getObject("best_of", Integer.class),
                rs.getInt("team_count")));
    }

    public long countEnrichedSeriesByState(Set<String> states) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM live_series WHERE state = ANY(:states)",
                Map.of("states", states.toArray(new String[0])), Long.class);
    }

    public List<TeamRow> findLiveTeams(String cursor, Set<String> states, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("states", states.toArray(new String[0]));

        String sql;
        if (cursor != null && !cursor.isBlank()) {
            sql = """
                SELECT id, name, abbreviation, player_count, series_ids FROM live_teams t
                WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(t.series_ids) AND s.state = ANY(:states))
                AND id > :cursor
                ORDER BY id ASC LIMIT :limit
                """;
            params.put("cursor", cursor);
        } else {
            sql = """
                SELECT id, name, abbreviation, player_count, series_ids FROM live_teams t
                WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(t.series_ids) AND s.state = ANY(:states))
                ORDER BY id ASC LIMIT :limit
                """;
        }

        return jdbc.query(sql, params, (rs, n) -> new TeamRow(
                rs.getString("id"), rs.getString("name"), rs.getString("abbreviation"),
                rs.getInt("player_count"), arrayToList(rs.getArray("series_ids"))));
    }

    public long countLiveTeamsByState(Set<String> states) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM live_teams t
            WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(t.series_ids) AND s.state = ANY(:states))
            """, Map.of("states", states.toArray(new String[0])), Long.class);
    }

    public List<PlayerRow> findLivePlayers(String cursor, Set<String> states, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        params.put("states", states.toArray(new String[0]));

        String sql;
        if (cursor != null && !cursor.isBlank()) {
            sql = """
                SELECT id, nickname, first_name, last_name, role, team_id, team_name, series_ids FROM live_players p
                WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(p.series_ids) AND s.state = ANY(:states))
                AND id > :cursor
                ORDER BY id ASC LIMIT :limit
                """;
            params.put("cursor", cursor);
        } else {
            sql = """
                SELECT id, nickname, first_name, last_name, role, team_id, team_name, series_ids FROM live_players p
                WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(p.series_ids) AND s.state = ANY(:states))
                ORDER BY id ASC LIMIT :limit
                """;
        }

        return jdbc.query(sql, params, (rs, n) -> new PlayerRow(
                rs.getString("id"), rs.getString("nickname"),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("role"), rs.getString("team_id"), rs.getString("team_name"),
                arrayToList(rs.getArray("series_ids"))));
    }

    public long countLivePlayersByState(Set<String> states) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM live_players p
            WHERE EXISTS (SELECT 1 FROM live_series s WHERE s.id = ANY(p.series_ids) AND s.state = ANY(:states))
            """, Map.of("states", states.toArray(new String[0])), Long.class);
    }

    // ── HELPERS ──────────────────────────────────────────────────────────────────

    private static Timestamp parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Timestamp.from(Instant.parse(iso)); } catch (Exception e) { return null; }
    }

    private static Timestamp safeTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : Timestamp.from(Instant.now());
    }

    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value : null;
    }

    private static String[] toSqlArray(Set<String> values) {
        return values == null ? new String[0] : values.toArray(new String[0]);
    }

    private static List<String> arrayToList(Array sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object[] arr = (Object[]) sqlArray.getArray();
            return Stream.of(arr).map(o -> o != null ? o.toString() : null).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── INNER TYPES ───────────────────────────────────────────────────────────────

    public record TeamRow(String id, String name, String abbreviation, int playerCount, List<String> seriesIds) {}
    public record PlayerRow(String id, String nickname, String firstName, String lastName,
                            String role, String teamId, String teamName, List<String> seriesIds) {}
}
