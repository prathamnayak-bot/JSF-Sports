# 🏏 JSF-Sports — AI Cricket Analytics & Fantasy Team Advisor

Mini-project for **JAVA and Spring Framework Lab (CS3603-1)**, V Semester CSE, AY 2026-27 · Team **24CSC21**, Section C.

Ask questions about real cricket stats in plain English — *"Which batter has the highest strike rate in the
death overs over the last 10 T20 matches?"* — and get a data-grounded answer. An LLM translates the question
into SQL (text-to-SQL), the backend runs it safely against a PostgreSQL database of ball-by-ball data, and the
LLM explains the result like a scouting report. A fantasy advisor projects every player's fantasy points from
recent form, venue and opponent records and picks a Dream11-style XI with captain / vice-captain.

| USN | Name |
|---|---|
| NNM24CS187 | Prasthuthi G |
| NNM24CS188 | Pratham Nayak U |
| NNM24CS189 | Prathiksha M Suvarna |

Approved abstract: [`docs/abstract/`](docs/abstract/) · Design notes: [`docs/architecture.md`](docs/architecture.md)

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4 (Web MVC, Data JPA / Hibernate, Validation, Actuator) |
| Database | PostgreSQL 17 (Docker) · H2 in-memory for quick runs without Docker |
| Data | [Cricsheet](https://cricsheet.org) ball-by-ball JSON (importer included) |
| AI | Any OpenAI-compatible LLM API — Groq (gpt-oss-120b, free tier), OpenAI (GPT-4o-mini) or local Ollama (qwen2.5-coder) |
| Frontend | React 19 + Vite |

## Repository layout

```
JSF-Sports/
├── backend/                     Spring Boot app (Maven)
│   └── src/main/java/com/jsf/cricket/
│       ├── domain/              JPA entities: Team, Player, Venue, CricketMatch, Innings
│       ├── repository/          Spring Data JPA repositories
│       ├── ingest/              Cricsheet JSON importer + /api/admin/import
│       ├── llm/                 LLM client (OpenAI-compatible chat completions)
│       ├── chat/                Text-to-SQL pipeline, SQL safety guard, /api/chat
│       ├── fantasy/             Fantasy points, projections, XI selection, /api/fantasy
│       ├── api/                 Browse/stats REST endpoints
│       ├── config/              CORS, LLM settings
│       └── common/              Error handling
│   └── src/main/resources/
│       ├── schema.sql           Database schema (also sent to the LLM as context)
│       └── application*.properties
├── frontend/                    React app (chat, fantasy XI, explore pages)
├── data/                        Cricsheet downloads go here (git-ignored)
├── docs/                        Abstract, architecture notes
├── docker-compose.yml           Local PostgreSQL
└── .env.example                 Template for your local secrets
```

## Getting started

**You need:** Git, JDK 21+, Node.js 20+, and Docker Desktop (optional — see the H2 option below).
Maven is not needed; the project uses the Maven wrapper (`mvnw`).

```bash
git clone https://github.com/<owner>/JSF-Sports.git
cd JSF-Sports
cp .env.example .env          # then paste your LLM_API_KEY into .env
```

Get a free LLM key from [Groq](https://console.groq.com/keys) (or use OpenAI / Ollama — see `.env.example`).

**1. Start the database**

```bash
docker compose up -d
```

**2. Start the backend** (http://localhost:8080)

```bash
cd backend
./mvnw spring-boot:run           # Windows CMD/PowerShell: mvnw.cmd spring-boot:run
```

No Docker? Run with the in-memory H2 database instead (data is lost on restart):
`./mvnw spring-boot:run -Dspring-boot.run.profiles=h2`

**3. Load some cricket data** — follow [`data/README.md`](data/README.md) (download a Cricsheet zip, then call the import endpoint).

**4. Start the frontend** (http://localhost:5173)

```bash
cd frontend
npm install
npm run dev
```

> **Port 8080 already in use** (e.g. by XAMPP/Apache)? Run the backend on another port and point the frontend at it:
> `SERVER_PORT=8081 ./mvnw spring-boot:run` and `BACKEND_URL=http://localhost:8081 npm run dev`
> (PowerShell: `$env:SERVER_PORT=8081` / `$env:BACKEND_URL="http://localhost:8081"` first).

> **Behind an antivirus / proxy that inspects HTTPS?** If you see `PKIX path building failed`, tell Java to use
> the Windows certificate store — for Maven downloads:
> `$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`, and for the app's calls to the LLM API:
> `./mvnw spring-boot:run "-Dspring-boot.run.jvmArguments=-Djavax.net.ssl.trustStoreType=Windows-ROOT"`.

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/chat` | `{"question": "..."}` → `{question, sql, rows, answer}` |
| `GET` | `/api/stats/overview` | Counts of matches, teams, players, venues, balls |
| `GET` | `/api/fantasy/suggest?team1=&team2=&venue=&explain=true` | Fantasy XI with captain / vice-captain (venue optional) |
| `GET` | `/api/teams` | All teams |
| `GET` | `/api/venues` | All venues |
| `GET` | `/api/players?q=kohli` | Search players by name |
| `GET` | `/api/players/{id}/summary` | Career batting + bowling summary |
| `GET` | `/api/matches?limit=20` | Most recent matches |
| `POST` | `/api/admin/import` | `{"directory": "..."}` — import a folder of Cricsheet JSON files |
| `GET` | `/actuator/health` | Health check |

## Running tests

```bash
cd backend && ./mvnw test          # importer, stats, SQL-guard and fantasy tests (uses H2, no Docker needed)
cd frontend && npm run lint && npm run build
```

GitHub Actions runs both on every push and pull request.

## Roadmap

- [x] Project setup, schema, Cricsheet importer, stats endpoints
- [x] Text-to-SQL chat with SQL safety guard and read-only execution
- [x] React chat + explore UI
- [x] Fantasy advisor: form score, venue/matchup factors, playing XI with captain / vice-captain
- [ ] Charts (Chart.js) for player form and team trends
- [ ] Scheduled sync job (`@Scheduled`) for new Cricsheet matches + CricketData.org fixtures
- [ ] Player roles and batting/bowling styles
- [ ] Separate read-only database user for chat queries

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the branch / pull-request workflow.
