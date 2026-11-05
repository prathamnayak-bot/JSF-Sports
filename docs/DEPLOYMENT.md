# Deploying online (Render + Neon, free)

The website is hosted on **Render** and the database on **Neon**. Both have free plans and you can sign in with GitHub.

```
browser ──► jsf-sports (Render static site, React)
        └─► jsf-sports-api (Render web service, Spring Boot in Docker) ──► Neon PostgreSQL
                                                                     └─► Groq LLM, CricketData.org
```

Free-plan limits to know before a demo:

- **Cold start.** The API goes to sleep after 15 minutes without requests, and the first request after that takes about a minute. **Open the site about 5 minutes before the demo** and keep `docker compose up` running locally as a backup.
- Neon also sleeps when idle, and wakes up within a second or two. The app keeps no idle database connections (`minimum-idle=0`), so Neon is free to sleep.
- The API has 512 MB of memory. `render.yaml` caps Java with `JAVA_TOOL_OPTIONS`. It uses about 260 MB.

## 1. Database: Neon

1. Sign in at https://neon.tech and create a project in **AWS Asia Pacific (Singapore)**, the same region as the API in `render.yaml`.
2. On the project dashboard, click **Connect** and copy the host, database, user and password. The JDBC URL is:
   `jdbc:postgresql://<host>/<database>?sslmode=require&reWriteBatchedInserts=true`
3. Copy the data from your local Docker database (about 55 MB) into Neon. This runs `pg_dump`/`psql` from the Postgres image, so you don't need to install anything:

   ```bash
   docker compose up -d postgres
   docker exec cricket-postgres pg_dump -U cricket -d cricket --no-owner --no-acl \
     | docker run -i --rm postgres:17-alpine psql "postgresql://<user>:<password>@<host>/<database>?sslmode=require"
   ```

   Or skip this step, deploy first, and load the data from Cricsheet with `POST /api/admin/sync` (see step 3).

4. Optional: create the read-only user for the AI chat. In Neon's **SQL Editor**, run the following, using your own password and your database's owner role in place of `neondb_owner`:

   ```sql
   CREATE ROLE cricket_readonly LOGIN PASSWORD '<pick a password>';
   ALTER ROLE cricket_readonly SET statement_timeout = '10s';
   ALTER ROLE cricket_readonly SET default_transaction_read_only = on;
   GRANT USAGE ON SCHEMA public TO cricket_readonly;
   GRANT SELECT ON ALL TABLES IN SCHEMA public TO cricket_readonly;
   ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public GRANT SELECT ON TABLES TO cricket_readonly;
   ```

   Render then asks for `CHAT_DB_PASSWORD`. If you skip this step, delete `CHAT_DB_USERNAME` from the API's environment on Render; chat queries still run in read-only transactions behind the SQL guard.

## 2. Website and API: Render

1. Sign in at https://render.com with GitHub and allow access to the `JSF-Sports` repository.
2. Choose **New → Blueprint**, then pick the repository. Render reads `render.yaml` and proposes two services: `jsf-sports-api` and `jsf-sports`.
3. Fill in the values Render asks for:

   | Variable | Value |
   |---|---|
   | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from Neon (step 1) |
   | `LLM_API_KEY` | your Groq key |
   | `CRICKETDATA_API_KEY` | your CricketData.org key (optional) |
   | `CORS_ORIGINS` | the website's address, e.g. `https://jsf-sports.onrender.com` |
   | `VITE_API_URL` | the API's address, e.g. `https://jsf-sports-api.onrender.com` |

   The service names decide both addresses: `https://<name>.onrender.com`. If a name is taken, Render adds a suffix. In that case, update `CORS_ORIGINS` and `VITE_API_URL` afterwards, and redeploy the static site, because `VITE_API_URL` is built into the JavaScript.

4. Click **Apply**. The first API build takes about 5–10 minutes.

## 3. Check it

- `https://<api>/actuator/health` returns `{"status":"UP"}`.
- `https://<api>/api/stats/overview` shows the match counts. If they are all 0, load the data:

  ```bash
  curl -X POST -H "X-Admin-Token: <ADMIN_TOKEN>" https://<api>/api/admin/sync
  ```

  Render generates `ADMIN_TOKEN`; you can see it under the API service's **Environment** tab. Without the token, `/api/admin/**` returns 401.
- Open the website and ask the chat a question.

## Protection for the public site

| What | How | Setting |
|---|---|---|
| Admin endpoints (import, sync, fixture refresh) | need the header `X-Admin-Token` | `ADMIN_TOKEN` (endpoints are open when it is not set, e.g. locally) |
| LLM quota (chat, fantasy `explain=true`) | 10 requests per browser per minute, 300 per day for the whole server; then HTTP 429 | `LLM_RATE_PER_MINUTE`, `LLM_RATE_PER_DAY` (`0` = no limit) |
| Secrets | only in Render's environment settings, never in the repository | — |
