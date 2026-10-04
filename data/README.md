# Cricket data

Match data comes from [Cricsheet](https://cricsheet.org/downloads/) (free, ball-by-ball, CC BY 4.0).

## Easiest: let the backend download it

With the backend running:

```bash
curl -X POST http://localhost:8080/api/admin/sync
```

This downloads the datasets listed in `app.sync.datasets` (default: `ipl`, about 5 MB), imports every match
that isn't in the database yet, and deletes the download. Run it again any time to pick up new matches.

To keep the database up to date automatically, set `SYNC_ENABLED=true` in `.env` — the backend then syncs
every day at 04:00 (change with `SYNC_CRON`). Add more competitions with e.g. `SYNC_DATASETS=ipl,t20s`
(names as on the Cricsheet downloads page: `t20s`, `odis`, `bbl`, `psl`, ...).

> The H2 profile keeps data in memory only, so run the sync again after each restart.

## Manual: import an unzipped folder

1. Download a JSON zip from Cricsheet, e.g. `ipl_json.zip`, and unzip it into this folder (e.g. `data/ipl_json/`).
   The files are large, so they are **not committed**.
2. Import it (use the absolute path on your machine):

   ```bash
   curl -X POST http://localhost:8080/api/admin/import \
        -H "Content-Type: application/json" \
        -d '{"directory": "C:/Users/<you>/.../JSF-Sports/data/ipl_json"}'
   ```

Already-imported matches are always skipped, so both ways are safe to repeat.
