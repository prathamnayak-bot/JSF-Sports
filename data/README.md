# Cricket data

Match data comes from [Cricsheet](https://cricsheet.org/downloads/) (free, ball-by-ball, CC BY 4.0).
The files are large, so they are **not committed** — each teammate downloads them into this folder.

1. Download a JSON zip, e.g. **Indian Premier League** (`ipl_json.zip`) or **T20 Internationals** (`t20s_json.zip`).
2. Unzip it into a folder here, e.g. `data/ipl_json/`.
3. With the backend running, import it (use the absolute path on your machine):

   ```bash
   curl -X POST http://localhost:8080/api/admin/import \
        -H "Content-Type: application/json" \
        -d '{"directory": "C:/Users/<you>/.../JSF-Sports/data/ipl_json"}'
   ```

   Already-imported matches are skipped, so it is safe to run again after downloading newer files.
