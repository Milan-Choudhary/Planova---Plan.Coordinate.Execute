# DECISIONS

| ID | Date | Decision | Why | Rejected / trade-off |
|---|---|---|---|---|
| D-001 | 2026-10-14 | Modular monolith: React + Spring Boot (Java 21) + PostgreSQL + Flyway | Per brief; runs on a laptop | Kafka/Redis/microservices – no requirement |
| D-002 | 2026-10-14 | PostgreSQL & services run from Docker images; credentials only in `.env`; `docker-compose.yml` and `application.yml` hold `${VAR}` references | Requested by team | Hard-coded values |
| D-003 | 2026-10-14 | One migration file per module (V1 identity, V2 planning, V3 incidents, V4 automation); full MVP schema defined up front | Data model must be agreed before backend/frontend diverge | Per-phase schema – risks later breaking changes |
| D-004 | 2026-10-14 | One global role per user (ADMIN/ORGANIZER/TEAM_MEMBER/VIEWER); per-plan rights via `plan_member` (Phase 3) | Simplest model satisfying the brief | Multi-role join table – unneeded complexity |
| D-005 | 2026-10-14 | Stateless JWT (HS256, Spring's built-in Nimbus support), BUT user status and role are re-read from DB on every request | Immediate revocation / role change without a token blacklist; forged `role` claim is ignored | Pure stateless (stale roles); sessions (cookies+CSRF). Cost: 1 PK lookup per request |
| D-006 | 2026-10-14 | Frontend keeps the token in `sessionStorage` | Survives refresh, cleared on tab close | httpOnly cookie is safer against XSS but needs CSRF handling; revisit if time allows |
| D-007 | 2026-10-14 | DB enforces invariants: one open assignment per incident, unique `(incident_id, sequence)`, one workflow execution per incident, unique notification `dedupe_key`, idempotency key per reporter | Races and duplicate events fail loudly at the DB instead of corrupting state | App-level checks only |
| D-008 | 2026-10-14 | Reassign/escalate = **UPDATE old assignment to SUPERSEDED, then INSERT new**, as two statements in one transaction | Verified on PG16: a single-statement CTE violates the partial unique index (checked per statement) | – |
| D-009 | 2026-10-14 | `audit_record` is append-only via trigger (UPDATE/DELETE/TRUNCATE blocked); no FK to users | History must outlive users | Claimed as tamper-**resistant**, not immutable – a DB superuser can drop the trigger |
| D-010 | 2026-10-14 | `AuditService` uses `Propagation.MANDATORY` | Audit row commits with the business change or neither | – |
| D-011 | 2026-10-14 | Spring Boot 3.5.0 / springdoc 2.8.9 | Familiar API surface | **Unverified** (no Maven Central access); check for newer patch / Boot 4 later |
| D-012 | 2026-10-14 | Backend tests use Testcontainers (real PostgreSQL), not H2 | Constraints/partial indexes/triggers are PostgreSQL-specific | Needs Docker to run tests |

## Known limitations
- No login rate-limiting or lockout yet (brute force possible).
- No refresh tokens: users re-login when the token expires (default 60 min).
- Token revocation relies on the per-request DB check, not a blacklist.
- Last-admin guard in `UserService.update` is best-effort; two simultaneous demotions could still race (to be covered by a concurrency test).

## Phases 3–6 decisions
| ID | Date | Decision | Why | Rejected / trade-off |
|---|---|---|---|---|
| D-013 | 2026-10-25 | Reassign and escalation are **not** status changes: they create assignment #n+1 while the incident stays ASSIGNED | One open assignment at all times; "who has it" is a single row | Extra ESCALATED status – two sources of truth |
| D-014 | 2026-10-25 | `@TransactionalEventListener(AFTER_COMMIT)` + recovery sweep instead of a transactional outbox | Same crash guarantee for an MVP with far less code | Outbox – more tables/jobs than the brief needs |
| D-015 | 2026-10-25 | Anything run from an afterCommit callback uses `PROPAGATION_REQUIRES_NEW` | The committed transaction is still bound to the thread; joining it would silently drop writes | – |
| D-016 | 2026-10-25 | Scheduler only finds candidates; the decision is re-made under the incident row lock | A stale job can never overwrite a newer ack/resolve | Deciding from the scan result |
| D-017 | 2026-10-25 | Plain `JdbcTemplate` for planning/incident/audit/notification code; JPA only for `User` | Hand-written guarded SQL is what the concurrency design needs, and it is testable on real PG | JPA entities + `@Version` everywhere |
| D-018 | 2026-10-25 | 404 (not 403) for plans/incidents the caller cannot read; 403 only when readable but not allowed | Does not leak existence | – |
| D-019 | 2026-10-25 | Actions are idempotent: repeating one that already took effect returns the current state; stale ex-assignee gets 409 `NOT_CURRENT_ASSIGNEE` | Double-clicks and retries are safe; "you lost the race" is not a permissions error | – |
| D-020 | 2026-10-25 | API returns `allowedActions` per incident; UI renders buttons from it | One implementation of the rules (`IncidentPolicy`); server still re-checks | Re-implementing rules in the UI |
| D-021 | 2026-10-25 | Only the IN_APP notification channel exists | Email needs SMTP infrastructure; faking it would misreport behaviour | Stub that logs "sent" |
| D-022 | 2026-10-25 | Verifier script mirrors the Java orchestration in Python against the real SQL | Maven unavailable in the authoring sandbox | Risk: mirror can drift from the services – integration tests (not yet run) are the real check |

## Known limitations (additions)
- Exhausted / no-responder executions are not auto-retried; an admin must assign manually.
- If no ACTIVE workflow definition exists, workflow failures are logged only.
- Workflow definition is view-only (`GET /admin/workflow`).
- Email notifications and task-deadline reminders are not implemented.
- Scheduler assumes one backend instance (correct with more, but all would scan).

## Planned decisions for the next implementation

| ID | Decision | Reason |
|---|---|---|
| D-023 | Serialize edits by workflow name with `pg_advisory_xact_lock`. Check the expected version, retire the active row, then insert the next immutable version in the same transaction. Resolve escalation settings from the execution's stored definition ID. | The lock survives replacement of the active row. Retiring first satisfies the partial unique index. Existing executions keep their settings. |
| D-024 | Write an in-app notification and, when enabled and opted in, a separate email row in the business transaction. Deliver each by its channel after commit. | Each channel has its own deduplication and retry state. Inbox queries can filter to in-app rows. |
| D-025 | Offer an optional severity suggestion with enum validation, a rule-based floor, and rule fallback. The reporter chooses a severity and reporting applies the existing severity policy. | The model can assist the reporter while the application retains control over the persisted value. |
