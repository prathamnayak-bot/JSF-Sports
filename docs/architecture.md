# Architecture

```
 React (Vite)                 Spring Boot backend                              PostgreSQL
┌────────────┐  POST /api/chat ┌──────────────────────────────────────────┐   ┌───────────┐
│ Chat page  │ ───────────────▶│ ChatService                              │   │ team      │
│            │                 │  1. question + schema.sql ──▶ LLM ──▶ SQL │   │ player    │
│            │                 │  2. SqlGuard: single SELECT only          │   │ venue     │
│            │                 │  3. run in READ-ONLY transaction ────────▶│──▶│ cricket_  │
│            │                 │  4. rows + question ──▶ LLM ──▶ answer    │   │   match   │
│            │◀─────────────── │                                          │   │ innings   │
│ Explore    │  GET /api/...   │ CricketController / StatsQueries ───────▶│──▶│ delivery  │
└────────────┘                 │                                          │   │ match_    │
                               │ CricsheetImporter ◀── Cricsheet JSON     │──▶│   player  │
                               └──────────────────────────────────────────┘   └───────────┘
```

## Data model

Cricsheet provides one JSON file per match with ball-by-ball detail. The importer maps it to:

- **team**, **venue**, **player** — created on first sight (players keyed by Cricsheet's stable registry id)
- **cricket_match** — format, event, date, venue, toss, result, player of the match
- **match_player** — each side's playing XI
- **innings** — per-innings totals (runs, wickets, legal balls) for fast queries
- **delivery** — one row per ball: batter, bowler, runs, extras, wicket

Cricket rules encoded in the data:

- Wides and no-balls are not legal deliveries (`is_legal_ball = false`); a wide is not a ball faced by the batter.
- Byes and leg-byes are not charged to the bowler; wides and no-balls are.
- Run outs (and retirements) are not credited to the bowler.
- Super overs are skipped so that regular-play stats stay clean.
- The fielder credited with each catch / stumping / run out is stored (`fielder_id`); substitutes are skipped.
- Renamed franchises are merged under their current name (e.g. Delhi Daredevils -> Delhi Capitals).
- Venue names are normalised: Cricsheet writes the same ground several ways ("Wankhede Stadium",
  "Wankhede Stadium, Mumbai", "M.Chinnaswamy Stadium"), so the importer keeps the part before the first comma,
  tidies initials and maps renamed grounds (Feroz Shah Kotla -> Arun Jaitley Stadium). 60 names -> 36 grounds
  for the IPL data; without this, "most matches at a venue" answers were wrong.

## Text-to-SQL safety

LLM output is untrusted, so generated SQL passes three independent layers:

1. **`SqlGuard`** — rejects anything that isn't a single `SELECT`/`WITH` statement, SQL comments, and
   write/DDL keywords or `pg_*` functions (string literals are ignored when checking).
2. **Read-only transaction** — the query runs with `readOnly=true`, capped at 200 rows with a 10-second timeout.
3. **Read-only database user** (`ChatDatabase`) — with PostgreSQL, chat queries use a separate login,
   `cricket_readonly` (created by `docker/postgres/01-readonly-user.sql`), that only has `SELECT` permission,
   defaults to read-only transactions and has a 10-second `statement_timeout`. Even if layers 1 and 2 were
   bypassed, PostgreSQL answers `permission denied`. (With H2 there is no separate user; layers 1-2 apply.)

## Why `schema.sql` instead of Hibernate DDL

The schema file is both the DDL that creates the tables and the context given to the LLM. Its comments explain
cricket semantics (e.g. "over_number starts at 0, overs 16-19 are the death overs"), which noticeably improves
the SQL the model writes. Keep the comments accurate when you change the schema.

## Fantasy advisor

`GET /api/fantasy/suggest?team1=..&team2=..&venue=..`

1. **Squads** — each team's playing XI from its most recent match in the format.
2. **Fantasy points per match** (`FantasyScoring`) — Dream11-style T20 rules computed from ball-by-ball data:
   1/run, +1 per four, +2 per six, +8/+16 for 50/100; 25 per wicket, +8 bowled/LBW, +12 per maiden,
   +4/+8/+16 for 3/4/5 wickets; 8 per catch, 12 per stumping, 6 per run out.
3. **Projection** (`FantasyService`) —
   `projected = 0.6 × form + 0.2 × venue average + 0.2 × average vs opponent`.
   Form is a recency-weighted average of the last 10 matches (each older match weighs 0.85× the next);
   venue / opponent averages need at least 3 matches, otherwise form is used in their place.
4. **Roles** — wicket-keepers are players with stumpings; others are bowlers / all-rounders / batters from their
   average balls bowled and faced over the last 15 matches.
5. **Selection** (`TeamSelector`) — greedy pick of 11 under Dream11-style limits (1-4 WK, 3-6 BAT, 1-4 AR,
   3-6 BOWL, max 7 per team): fill each role's minimum with its best players, then the best remaining.
   The top two projections become captain (2×) and vice-captain (1.5×).
6. **Explanation** (optional) — the XI and its numbers are sent to the LLM for a short scouting-style summary.

The per-match aggregates are five simple grouped queries merged in Java: a single query with joined CTEs is
fast on PostgreSQL but takes minutes on H2, which re-evaluates CTEs per row.
