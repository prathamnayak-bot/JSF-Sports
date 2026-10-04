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

## Text-to-SQL safety

LLM output is untrusted, so generated SQL passes two independent checks:

1. **`SqlGuard`** — rejects anything that isn't a single `SELECT`/`WITH` statement, SQL comments, and
   write/DDL keywords or `pg_*` functions (string literals are ignored when checking).
2. **Read-only transaction** — the query runs with `readOnly=true`, so PostgreSQL refuses writes even if
   something slipped through. Results are capped at 200 rows with a 10-second timeout.

## Why `schema.sql` instead of Hibernate DDL

The schema file is both the DDL that creates the tables and the context given to the LLM. Its comments explain
cricket semantics (e.g. "over_number starts at 0, overs 16-19 are the death overs"), which noticeably improves
the SQL the model writes. Keep the comments accurate when you change the schema.
