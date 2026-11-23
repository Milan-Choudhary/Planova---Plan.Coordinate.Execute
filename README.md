# Planova AI – Smart Event & Operations Management Platform

BTech CSE major project. React + TypeScript frontend, Spring Boot (Java 21) modular monolith, PostgreSQL 16 (Docker), Flyway.
Plan events, assign tasks, and handle incidents with automatic assignment, acknowledgement, escalation, notifications and a full audit trail.

**Status** — The V1–V5 baseline has passed backend, frontend, and PostgreSQL checks. See [PROGRESS.md](PROGRESS.md) for verification results and pending features.
**Run it:** [SETUP.md](SETUP.md).
**Find your way around** — [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) maps the codebase and traces an incident from the frontend to the backend.

```
backend/    Spring Boot app, Flyway migrations (src/main/resources/db/migration)
frontend/   React + TypeScript (Vite)
scripts/    verify-migrations.sh, verify-incident-sql.sh (+ SQL/py checks against a throw-away PostgreSQL)
docs/       REQUIREMENTS · ARCHITECTURE · DATA_MODEL · STATE_MACHINES · EDGE_CASES · TEST_PLAN
docker-compose.yml, .env.example
```
Top-level docs: PROGRESS · DECISIONS · API_CONTRACTS · SETUP.
