# Team workflow

Keep `main` always working. All changes go through a branch and a pull request (PR).

## Day-to-day

```bash
git checkout main
git pull                                   # get everyone's latest work
git checkout -b feature/fantasy-advisor    # one branch per task
# ...code, then run the tests...
git add .
git commit -m "feat: add form score to fantasy advisor"
git push -u origin feature/fantasy-advisor
```

Then open a pull request on GitHub, ask a teammate to review it, and merge when CI is green.

## Branch names

| Prefix | Use for |
|---|---|
| `feature/` | New functionality (`feature/player-charts`) |
| `fix/` | Bug fixes (`fix/strike-rate-wides`) |
| `docs/` | README, report, diagrams |

## Commit messages

Start with a type: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:` — e.g. `fix: exclude wides from balls faced`.

## Before you push

- Backend: `cd backend && ./mvnw test`
- Frontend: `cd frontend && npm run lint && npm run build`
- Never commit `.env`, API keys, or files from `data/`.

## Where things go

- New database tables/columns → `backend/src/main/resources/schema.sql` (add a comment explaining each column —
  the LLM reads these comments) **and** the matching JPA entity in `domain/`.
- New REST endpoints → a controller in `api/` (or a new feature package like `fantasy/`).
- New UI pages → `frontend/src/components/`, API calls in `frontend/src/api.js`.
