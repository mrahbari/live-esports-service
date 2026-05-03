-- Consolidate to 3 tables: live_series, live_teams, live_players.
-- Replaces series_teams/series_players junction tables with series_ids arrays.
-- Merges the thin `series` tracking table into live_series.

-- 1. Add series_ids arrays
ALTER TABLE live_teams   ADD COLUMN IF NOT EXISTS series_ids TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE live_players ADD COLUMN IF NOT EXISTS series_ids TEXT[] NOT NULL DEFAULT '{}';

-- 2. Backfill arrays from existing junction tables
UPDATE live_teams t
SET series_ids = (
    SELECT COALESCE(array_agg(DISTINCT st.series_id), '{}')
    FROM series_teams st WHERE st.team_id = t.id
);

UPDATE live_players p
SET series_ids = (
    SELECT COALESCE(array_agg(DISTINCT sp.series_id), '{}')
    FROM series_players sp WHERE sp.player_id = p.id
);

-- 3. GIN indexes for efficient array-overlap queries
CREATE INDEX IF NOT EXISTS idx_live_teams_series_ids   ON live_teams   USING GIN (series_ids);
CREATE INDEX IF NOT EXISTS idx_live_players_series_ids ON live_players USING GIN (series_ids);

-- 4. Merge upcoming series from thin tracker into live_series
INSERT INTO live_series (id, state, started_at, resource_version, updated_at)
SELECT id::TEXT, lifecycle, start_time, resource_version, updated_at
FROM series
ON CONFLICT (id) DO NOTHING;

-- 5. Drop obsolete tables
DROP TABLE IF EXISTS series_teams;
DROP TABLE IF EXISTS series_players;
DROP TABLE IF EXISTS series;
