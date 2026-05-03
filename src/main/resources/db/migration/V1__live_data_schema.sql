CREATE TABLE live_series (
    id               VARCHAR(64)  PRIMARY KEY,
    name             VARCHAR(255),
    game_name        VARCHAR(100),
    state            VARCHAR(50),
    started_at       TIMESTAMPTZ,
    tier             INTEGER,
    best_of          INTEGER,
    team_count       INTEGER      NOT NULL DEFAULT 0,
    resource_version INTEGER,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_live_series_state_id   ON live_series (state, id);
CREATE INDEX idx_live_series_updated_at ON live_series (updated_at);

CREATE TABLE live_teams (
    id           VARCHAR(64)  PRIMARY KEY,
    name         VARCHAR(255),
    abbreviation VARCHAR(50),
    player_count INTEGER      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_live_teams_updated_at ON live_teams (updated_at);

CREATE TABLE live_players (
    id         VARCHAR(64)  PRIMARY KEY,
    nickname   VARCHAR(255),
    first_name VARCHAR(100),
    last_name  VARCHAR(100),
    role       VARCHAR(100),
    team_id    VARCHAR(64),
    team_name  VARCHAR(255),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_live_players_team_id    ON live_players (team_id);
CREATE INDEX idx_live_players_updated_at ON live_players (updated_at);

CREATE TABLE series_teams (
    series_id  VARCHAR(64)  NOT NULL,
    team_id    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (series_id, team_id)
);

CREATE INDEX idx_series_teams_team_id ON series_teams (team_id);

CREATE TABLE series_players (
    series_id  VARCHAR(64)  NOT NULL,
    player_id  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (series_id, player_id)
);

CREATE INDEX idx_series_players_player_id ON series_players (player_id);
