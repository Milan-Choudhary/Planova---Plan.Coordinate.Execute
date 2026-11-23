# PROGRESS

## Workspace check on 2026-11-22

- This source snapshot has Flyway V1–V5, in-app notifications, read-only workflow administration, and keyword-only incident severity. The email and reminder, workflow editor, and AI implementations described in the handover are absent. D-023–D-025 record the planned designs.
- Maven compiled 64 application sources with Java 21 as the target using JDK 25. The initial test compilation exposed a name collision with MockMvc's `status()` matcher. The incident test helper is now named `incidentStatus`.
- On Node 24, `npm ci`, `npm run typecheck`, `npm test` (35 passed), and `npm run build` succeeded.
- PostgreSQL 16 became healthy in Docker and Spring Boot started. The host-side launch needed the explicit `dev` profile to seed demo users. Flyway applied V1–V5 against the fresh Testcontainers database.
- All 74 Maven tests passed, including 45 PostgreSQL integration tests. This Docker Desktop setup required `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` and `-Dapi.version=1.44` for Testcontainers. Admin user creation now trims the submitted email before Bean Validation, as covered by the existing normalization test.
- Both PostgreSQL scripts passed in a disposable PostgreSQL 16 container. The incident SQL verifier reported 76 passed and 0 failed.
- Local HTTP smoke test passed for demo login, HIGH-severity incident report, automatic assignment, in-app delivery, acknowledgement, resolution, and incident history (four audit entries). Vite returned HTTP 200. Overdue escalation passed in the backend integration suite with the movable clock.
- Both Compose images built. The frontend image also rebuilt with a `.dockerignore` that excludes host dependencies, build output, coverage, and local environment files.

## Historical authoring notes from 2026-10-25

## Verified state (what was actually executed)

| Check | Result | How |
|---|---|---|
| Flyway V1–V5 applied to real PostgreSQL 16 + 28 schema assertions | **PASSED** | `scripts/verify-migrations.sh` |
| Incident SQL (every statement in `IncidentSql`) + 6 concurrent race scenarios, real PG16, concurrent connections | **PASSED, 76/76** | `scripts/verify-incident-sql.sh` |
| Pure-Java domain rules (state machine, severity, responder selection, workflow config, policy, task status) | **PASSED, 25/25** | `javac` + minimal JUnit shim (the real JUnit jar is not available offline) |
| Frontend `tsc --noEmit` | **PASSED** | `npm run typecheck` |
| Frontend tests | **PASSED, 35/35** | `npm test` |
| Frontend production build | **PASSED** | `npm run build` |
| `docker compose config` (with required variables set) | **PASSED** | CLI parse only; no containers started |
| Java **syntax** parse of every backend + test file | No syntax errors (only missing-library errors, expected) | `javac` |
| **Backend compile** (`mvn`) | **NOT RUN** – Maven Central blocked (HTTP 403) | – |
| **Backend integration tests** (49 written: auth 24, security defaults 6, planning 5, incident workflow 10, properties 4) | **NOT RUN** – need Maven + Docker | – |
| `docker compose up`, image builds | **NOT RUN** – no Docker daemon, Docker Hub blocked | – |
| End-to-end through the browser | **NOT RUN** | – |

**What this means:** the database design, the critical SQL and its concurrency behaviour, the business rules and the whole frontend are verified. The Spring layer (controllers, services, wiring, ~60 Java files) is complete and carefully written but has never been compiled. Expect a small number of compile or wiring errors on the first `mvn verify`; fix them before trusting any green/red result from the integration tests. The Python verifier mirrors the Java orchestration, so it proves the SQL, not the Java services (DECISIONS D-022).

## Done
| Phase | Status |
|---|---|
| 1 Foundation – Docker Postgres, `.env`/`.yml` split, Flyway V1–V5, error shape | done, schema verified |
| 2 Authentication – BCrypt, JWT, per-request role/status re-read, admin user API | written, uncompiled |
| 3 Planning – teams, plans, members, events, tasks, `AccessService` | written, uncompiled |
| 4 Incident workflow – report (idempotent), state machine, assign/ack/reject/start/resolve/close/reopen | rules + SQL verified; Spring layer uncompiled |
| 5 Automation – auto-assign, escalation, recovery sweep, notifications with bounded retry, scheduler | SQL verified; Spring layer uncompiled |
| 6 Frontend integration – dashboard, plans (events/tasks/incidents/members/audit), incidents, teams, notifications, my tasks, admin users/audit | verified (35 tests) against mocked API; never run against the real backend |
| 7 Verification – tests, docs, demo seed | tests written; docs complete |
| 8 Optional AI | **not started** (by design: core first) |

## Still to do (honest list)
1. **Run `mvn verify` on a machine with internet + Docker; fix compile/wiring errors.** (blocking)
2. Run the stack (`SETUP.md`) and walk the manual acceptance script in `docs/TEST_PLAN.md`.
3. `docker compose --profile app up --build` to prove the images build.
4. Hardening: login rate limiting, refresh tokens.
5. Email channel, task reminders, workflow-definition editing UI/API.
6. Phase 8 optional AI (e.g. incident severity suggestion) – only after 1–3.

## Known risks in uncompiled code
- Spring Boot 3.5.0 / springdoc 2.8.9 were chosen from memory, not resolved.
- `DatabaseBackedJwtConverter` throws `InvalidBearerTokenException` to get a 401 – confirm via `AuthIntegrationTest`.
- `application.yml` imports `../.env` – confirm startup from `backend/` with only `.env`.
- `IncidentService.list` / `TaskService.mine` build SQL by string composition with `?` placeholders; the argument order matters (reviewed by hand, not executed).
- `TaskService.mine` rewrites `AccessService.VISIBLE_PLAN_PREDICATE` with string replacement – first thing to check if that endpoint misbehaves.
- Integration tests are not `@Transactional` on purpose (AFTER_COMMIT must fire); they isolate by unique data.
