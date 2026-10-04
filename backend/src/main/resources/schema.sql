-- Cricket analytics schema.
-- This file is the single source of truth: it creates the tables on startup
-- AND is sent to the LLM as context for text-to-SQL, so keep the comments accurate.

-- A franchise or national side, e.g. 'Chennai Super Kings', 'India'. Renamed franchises are stored under
-- their current name (e.g. 'Delhi Daredevils' -> 'Delhi Capitals', 'Kings XI Punjab' -> 'Punjab Kings').
CREATE TABLE IF NOT EXISTS team (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE
);

-- A player. cricsheet_id is the stable id from Cricsheet's people registry.
-- role is one of: BATTER, BOWLER, ALL_ROUNDER, WICKET_KEEPER (may be NULL if unknown).
CREATE TABLE IF NOT EXISTS player (
    id            BIGSERIAL PRIMARY KEY,
    cricsheet_id  VARCHAR(16) UNIQUE,
    name          VARCHAR(120) NOT NULL,
    role          VARCHAR(20),
    batting_style VARCHAR(40),
    bowling_style VARCHAR(60)
);

-- A ground, e.g. 'MA Chidambaram Stadium' (city 'Chennai'). Names are normalised: no city suffix,
-- renamed grounds under their current name (e.g. 'Feroz Shah Kotla' -> 'Arun Jaitley Stadium').
CREATE TABLE IF NOT EXISTS venue (
    id     BIGSERIAL PRIMARY KEY,
    name   VARCHAR(200) NOT NULL UNIQUE,
    city   VARCHAR(100)
);

-- One match (table is named cricket_match because MATCH is an SQL keyword). format is one of: T20, ODI, TEST, IT20, ODM, MDM.
-- event_name is the tournament, e.g. 'Indian Premier League'.
-- toss_decision is 'bat' or 'field'. winner_team_id is NULL for a tie / no result.
-- result_margin + result_type describe the win, e.g. 5 + 'wickets' or 23 + 'runs'.
CREATE TABLE IF NOT EXISTS cricket_match (
    id              BIGSERIAL PRIMARY KEY,
    cricsheet_id    VARCHAR(32) UNIQUE,
    format          VARCHAR(10) NOT NULL,
    event_name      VARCHAR(200),
    season          VARCHAR(20),
    match_date      DATE NOT NULL,
    venue_id        BIGINT REFERENCES venue(id),
    team1_id        BIGINT NOT NULL REFERENCES team(id),
    team2_id        BIGINT NOT NULL REFERENCES team(id),
    toss_winner_id  BIGINT REFERENCES team(id),
    toss_decision   VARCHAR(10),
    winner_team_id  BIGINT REFERENCES team(id),
    result_margin   INT,
    result_type     VARCHAR(20),
    player_of_match_id BIGINT REFERENCES player(id)
);

-- Which players were in each team's playing XI for a match.
CREATE TABLE IF NOT EXISTS match_player (
    match_id   BIGINT NOT NULL REFERENCES cricket_match(id),
    team_id    BIGINT NOT NULL REFERENCES team(id),
    player_id  BIGINT NOT NULL REFERENCES player(id),
    PRIMARY KEY (match_id, player_id)
);

-- One innings of a match. innings_number starts at 1.
-- total_runs / total_wickets / legal_balls are denormalised totals for fast queries.
CREATE TABLE IF NOT EXISTS innings (
    id               BIGSERIAL PRIMARY KEY,
    match_id         BIGINT NOT NULL REFERENCES cricket_match(id),
    innings_number   INT NOT NULL,
    batting_team_id  BIGINT NOT NULL REFERENCES team(id),
    bowling_team_id  BIGINT NOT NULL REFERENCES team(id),
    total_runs       INT NOT NULL DEFAULT 0,
    total_wickets    INT NOT NULL DEFAULT 0,
    legal_balls      INT NOT NULL DEFAULT 0,
    UNIQUE (match_id, innings_number)
);

-- One ball bowled (ball-by-ball data).
-- over_number starts at 0 (so over_number 0 is the 1st over; overs 16-19 are the T20 death overs).
-- ball_in_over is the sequence within the over, including extras (wides/no-balls are not legal balls).
-- runs_batter = runs credited to the batter; runs_extras = extras on this ball; runs_total = both.
-- extra_type is NULL or one of: wides, noballs, byes, legbyes, penalty.
-- is_legal_ball is FALSE for wides and no-balls (use it to count balls faced / balls bowled;
-- note: a no-ball still counts as a ball faced by the batter, a wide does not).
-- wicket_kind is NULL or e.g. 'caught', 'bowled', 'lbw', 'run out', 'stumped'.
-- A bowler is credited with the wicket unless wicket_kind is 'run out', 'retired hurt',
-- 'retired out' or 'obstructing the field'.
-- fielder_id is the fielder credited with a catch, stumping or run out (NULL otherwise or for substitutes);
-- for 'caught and bowled' it is the bowler. Stumpings are made by the wicket-keeper.
CREATE TABLE IF NOT EXISTS delivery (
    id               BIGSERIAL PRIMARY KEY,
    innings_id       BIGINT NOT NULL REFERENCES innings(id),
    over_number      INT NOT NULL,
    ball_in_over     INT NOT NULL,
    batter_id        BIGINT NOT NULL REFERENCES player(id),
    bowler_id        BIGINT NOT NULL REFERENCES player(id),
    non_striker_id   BIGINT REFERENCES player(id),
    runs_batter      INT NOT NULL DEFAULT 0,
    runs_extras      INT NOT NULL DEFAULT 0,
    runs_total       INT NOT NULL DEFAULT 0,
    extra_type       VARCHAR(10),
    is_legal_ball    BOOLEAN NOT NULL DEFAULT TRUE,
    wicket_kind      VARCHAR(30),
    player_out_id    BIGINT REFERENCES player(id),
    fielder_id       BIGINT REFERENCES player(id)
);

-- for databases created before fielder_id existed
ALTER TABLE delivery ADD COLUMN IF NOT EXISTS fielder_id BIGINT REFERENCES player(id);

CREATE INDEX IF NOT EXISTS idx_delivery_innings ON delivery(innings_id);
CREATE INDEX IF NOT EXISTS idx_delivery_batter  ON delivery(batter_id);
CREATE INDEX IF NOT EXISTS idx_delivery_bowler  ON delivery(bowler_id);
CREATE INDEX IF NOT EXISTS idx_delivery_fielder ON delivery(fielder_id);
CREATE INDEX IF NOT EXISTS idx_match_date       ON cricket_match(match_date);
