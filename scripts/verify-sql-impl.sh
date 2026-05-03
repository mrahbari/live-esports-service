#!/usr/bin/env bash
# verify-sql-impl.sh — validates every SQL statement used by the polling and ingestion code
# against a live PostgreSQL instance. Runs against the postgres service in docker-compose.
#
# Usage:
#   ./scripts/verify-sql-impl.sh               # uses defaults (esports/esports)
#   POSTGRES_USER=myuser ./scripts/verify-sql-impl.sh
#
# Prereqs: docker-compose postgres service must be running.
set -euo pipefail

POSTGRES_USER="${POSTGRES_USER:-esports}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-esports}"
POSTGRES_DB="${POSTGRES_DB:-esports}"
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
# Docker container name (used when psql is not installed locally)
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-}"

PASS=0
FAIL=0
ERRORS=()

# Auto-detect: use docker exec if psql is not available locally
psql_cmd() {
    if [ -n "$POSTGRES_CONTAINER" ]; then
        docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            -v ON_ERROR_STOP=1 -q "$@"
    elif command -v psql &>/dev/null; then
        PGPASSWORD="$POSTGRES_PASSWORD" psql \
            -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" \
            -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            -v ON_ERROR_STOP=1 -q "$@"
    else
        # Fall back to docker-compose service name
        local container
        container=$(docker-compose ps -q postgres 2>/dev/null | head -1)
        if [ -z "$container" ]; then
            echo "[ERROR] psql not found and no postgres container running." >&2
            exit 1
        fi
        docker exec "$container" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            -v ON_ERROR_STOP=1 -q "$@"
    fi
}

check() {
    local label="$1"
    local sql="$2"
    if psql_cmd -c "$sql" > /dev/null 2>&1; then
        echo "  [PASS] $label"
        PASS=$((PASS+1))
    else
        echo "  [FAIL] $label"
        ERRORS+=("$label")
        FAIL=$((FAIL+1))
    fi
}

echo "========================================"
echo " SQL implementation verification"
echo " host=$POSTGRES_HOST:$POSTGRES_PORT db=$POSTGRES_DB"
echo "========================================"

# ── Connectivity ─────────────────────────────────────────────────────────────
echo ""
echo "--- Connectivity ---"
if ! psql_cmd -c "SELECT 1" > /dev/null 2>&1; then
    echo "[ERROR] Cannot connect to PostgreSQL. Is docker-compose up?"
    exit 1
fi
echo "  [PASS] connection OK"

# ── Schema: table and column existence ───────────────────────────────────────
echo ""
echo "--- Schema Check ---"
check "live_series table exists" \
    "SELECT 1 FROM live_series LIMIT 0"
check "live_series.resource_version column exists" \
    "SELECT resource_version FROM live_series LIMIT 0"
check "live_series.state column exists" \
    "SELECT state FROM live_series LIMIT 0"
check "live_series.started_at column exists" \
    "SELECT started_at FROM live_series LIMIT 0"

check "live_teams table exists" \
    "SELECT 1 FROM live_teams LIMIT 0"
check "live_teams.series_ids array column exists" \
    "SELECT series_ids FROM live_teams LIMIT 0"

check "live_players table exists" \
    "SELECT 1 FROM live_players LIMIT 0"
check "live_players.series_ids array column exists" \
    "SELECT series_ids FROM live_players LIMIT 0"

echo ""
echo "--- Obsolete Tables (Should be gone) ---"
if psql_cmd -c "SELECT 1 FROM series LIMIT 0" > /dev/null 2>&1; then
    echo "  [FAIL] 'series' table still exists"
    FAIL=$((FAIL+1))
else
    echo "  [PASS] 'series' table is gone"
    PASS=$((PASS+1))
fi

# ── JdbcLiveRepository: upsertEnrichedSeries ────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: upsertEnrichedSeries ---"
check "INSERT live_series with resource_version" \
    "INSERT INTO live_series (id, name, game_name, state, started_at, tier, best_of, team_count, resource_version, updated_at)
     VALUES ('test-v-001', 'Test Series', 'CS2', 'live', NULL, 2, 3, 2, 5, NOW())
     ON CONFLICT (id) DO UPDATE SET
         name             = EXCLUDED.name,
         game_name        = EXCLUDED.game_name,
         state            = EXCLUDED.state,
         started_at       = EXCLUDED.started_at,
         tier             = EXCLUDED.tier,
         best_of          = EXCLUDED.best_of,
         team_count       = EXCLUDED.team_count,
         resource_version = COALESCE(EXCLUDED.resource_version, live_series.resource_version),
         updated_at       = EXCLUDED.updated_at"

# ── JdbcLiveRepository: upsertEnrichedTeam ──────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: upsertEnrichedTeam ---"
check "INSERT live_teams with series_ids" \
    "INSERT INTO live_teams (id, name, abbreviation, player_count, series_ids, updated_at)
     VALUES ('test-team-001', 'Test Team', 'TT', 5, ARRAY['test-v-001'], NOW())
     ON CONFLICT (id) DO UPDATE SET
         name         = EXCLUDED.name,
         abbreviation = EXCLUDED.abbreviation,
         player_count = GREATEST(EXCLUDED.player_count, live_teams.player_count),
         series_ids   = ARRAY(SELECT DISTINCT unnest(live_teams.series_ids || EXCLUDED.series_ids)),
         updated_at   = EXCLUDED.updated_at"

# ── JdbcLiveRepository: upsertEnrichedPlayer ────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: upsertEnrichedPlayer ---"
check "INSERT live_players with series_ids" \
    "INSERT INTO live_players (id, nickname, first_name, last_name, role, team_id, team_name, series_ids, updated_at)
     VALUES ('test-pl-001', 's1mple', 'Oleksandr', 'Kostyliev', 'rifler', 'test-team-001', 'Test Team', ARRAY['test-v-001'], NOW())
     ON CONFLICT (id) DO UPDATE SET
         nickname   = EXCLUDED.nickname,
         first_name = EXCLUDED.first_name,
         last_name  = EXCLUDED.last_name,
         role       = EXCLUDED.role,
         team_id    = COALESCE(EXCLUDED.team_id, live_players.team_id),
         team_name  = COALESCE(EXCLUDED.team_name, live_players.team_name),
         series_ids = ARRAY(SELECT DISTINCT unnest(live_players.series_ids || EXCLUDED.series_ids)),
         updated_at = EXCLUDED.updated_at"

# ── JdbcLiveRepository: pruneStaleEnriched ──────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: pruneStaleEnriched ---"
check "Prune stale series IDs from teams" \
    "UPDATE live_teams SET series_ids = ARRAY(
        SELECT s FROM unnest(series_ids) s
        WHERE EXISTS (SELECT 1 FROM live_series WHERE id = s AND updated_at >= NOW())
    ) WHERE series_ids != '{}'"
check "Prune stale series IDs from players" \
    "UPDATE live_players SET series_ids = ARRAY(
        SELECT s FROM unnest(series_ids) s
        WHERE EXISTS (SELECT 1 FROM live_series WHERE id = s AND updated_at >= NOW())
    ) WHERE series_ids != '{}'"
check "DELETE stale live_series" \
    "DELETE FROM live_series WHERE updated_at < NOW() AND state != 'upcoming'"

# ── JdbcLiveRepository: findLiveSeries ──────────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: findLiveSeries ---"
check "findLiveSeries query" \
    "SELECT id, name, game_name, state, started_at, tier, best_of, team_count
     FROM live_series WHERE state = ANY(ARRAY['live','ongoing']) AND id > '0'
     ORDER BY id ASC LIMIT 50"

# ── JdbcLiveRepository: findLiveTeams ───────────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: findLiveTeams (Array overlap) ---"
check "findLiveTeams with array overlap &&" \
    "SELECT id, name, abbreviation, player_count, series_ids FROM live_teams
     WHERE series_ids && COALESCE((SELECT array_agg(id::TEXT) FROM live_series WHERE state = ANY(ARRAY['live'])), '{}'::TEXT[])
     AND id > '0'
     ORDER BY id ASC LIMIT 50"

# ── JdbcLiveRepository: findLivePlayers ─────────────────────────────────────
echo ""
echo "--- JdbcLiveRepository: findLivePlayers (Array overlap) ---"
check "findLivePlayers with array overlap &&" \
    "SELECT id, nickname, first_name, last_name, role, team_id, team_name, series_ids FROM live_players
     WHERE series_ids && COALESCE((SELECT array_agg(id::TEXT) FROM live_series WHERE state = ANY(ARRAY['live'])), '{}'::TEXT[])
     AND id > '0'
     ORDER BY id ASC LIMIT 50"

# ── PollingSeriesRepository: upsert ─────────────────────────────────────────
echo ""
echo "--- PollingSeriesRepository: upsert ---"
check "INSERT live_series (polling)" \
    "INSERT INTO live_series (id, state, started_at, resource_version, updated_at)
     VALUES ('99991', 'upcoming', NOW() + INTERVAL '5 days', 3, NOW())
     ON CONFLICT (id) DO UPDATE SET
         state            = EXCLUDED.state,
         started_at       = EXCLUDED.started_at,
         resource_version = COALESCE(EXCLUDED.resource_version, live_series.resource_version),
         updated_at       = EXCLUDED.updated_at"

# ── PollingSeriesRepository: markEndedIfStale ───────────────────────────────
echo ""
echo "--- PollingSeriesRepository: markEndedIfStale ---"
check "markEndedIfStale UPDATE query" \
    "UPDATE live_series SET state = 'ended', updated_at = NOW()
     WHERE state = 'upcoming' AND updated_at < NOW() - INTERVAL '1 minute'"

# ── PollingSeriesRepository: getByState ─────────────────────────────────────
echo ""
echo "--- PollingSeriesRepository: getByState ---"
check "getByState query" \
    "SELECT id, state, started_at, updated_at FROM live_series
     WHERE state = 'upcoming' AND id > '0'
     ORDER BY id ASC LIMIT 51"

# ── Index existence ──────────────────────────────────────────────────────────
echo ""
echo "--- Index existence ---"
check "idx_live_series_state_id" \
    "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_live_series_state_id'"
check "idx_live_teams_series_ids (GIN)" \
    "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_live_teams_series_ids'"
check "idx_live_players_series_ids (GIN)" \
    "SELECT 1 FROM pg_indexes WHERE indexname = 'idx_live_players_series_ids'"

# ── Cleanup test rows ────────────────────────────────────────────────────────
echo ""
echo "--- Cleanup test data ---"
psql_cmd -c "DELETE FROM live_series WHERE id IN ('test-v-001', '99991')" > /dev/null 2>&1
psql_cmd -c "DELETE FROM live_players WHERE id = 'test-pl-001'" > /dev/null 2>&1
psql_cmd -c "DELETE FROM live_teams WHERE id = 'test-team-001'" > /dev/null 2>&1

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "========================================"
echo " Results: ${PASS} passed, ${FAIL} failed"
echo "========================================"
if [ ${#ERRORS[@]} -gt 0 ]; then
    echo ""
    echo "Failed checks:"
    for e in "${ERRORS[@]}"; do
        echo "  - $e"
    done
    exit 1
fi
echo " All SQL checks passed."
