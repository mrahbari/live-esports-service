-- Polling-based series tracking table.
-- Separate from live_series (snapshot path); this table is owned by the polling workers.
CREATE TABLE series (
    id               BIGINT       PRIMARY KEY,
    lifecycle        VARCHAR(50)  NOT NULL,          -- live | upcoming | ended
    start_time       TIMESTAMPTZ,
    resource_version INTEGER,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Supports cursor pagination per lifecycle: WHERE lifecycle = ? AND id > ? ORDER BY id
CREATE INDEX idx_series_lifecycle_id   ON series (lifecycle, id);

-- Supports ended-detection sweep: WHERE lifecycle = ? AND last_seen_at < ?
CREATE INDEX idx_series_last_seen_at   ON series (last_seen_at);

-- Supports upcoming sort by start_time for display ordering
CREATE INDEX idx_series_start_time     ON series (start_time ASC)
    WHERE lifecycle = 'upcoming';
