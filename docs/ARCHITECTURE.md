# ARCHITECTURE

```
Browser (React/Vite) ──HTTP/JSON──▶ Spring Boot modular monolith ──JDBC/JPA──▶ PostgreSQL 16 (Docker)
                                      │
                                      └─ @Scheduled jobs (escalation, recovery sweep, notification retry)
```

## Backend modules (`com.planova.*`)
| Package | Responsibility |
|---|---|
| `common` | `ApiError`, `ApiException`, global handler, correlation id filter, `Db` helpers, `PageResponse` |
| `config` | Security (stateless JWT, default-deny), OpenAPI, `Clock` bean, scheduling switch |
| `identity` | Users, login, token issue/verify, `Actor` (id + current role), demo seeder (dev profile) |
| `access` | `AccessService`: the one place deciding "may this caller read/edit this plan?" |
| `planning` | Teams, plans, members, events, tasks |
| `incident` | State machine, severity policy, responder selection, assignment, escalation, workflow, scheduler |
| `notification` | Notification rows, in-app channel, dispatcher with bounded retry, inbox API |
| `audit` | Append-only writer (`MANDATORY` transaction) and query API |
| `dashboard`, `admin` | Summary numbers; read-only workflow definition |

## Layering rules
- Controllers parse + delegate. Services own rules and transactions. Repositories/SQL constants own statements.
- Pure rule classes (`IncidentStatus`, `SeverityPolicy`, `ResponderSelector`, `WorkflowConfig`, `IncidentPolicy`, `TaskStatus`) have no Spring dependency and are unit-tested directly.
- **All correctness-critical SQL lives in `IncidentSql`** and is executed against real PostgreSQL by `scripts/verify_incident_sql.py`.

## The incident pipeline
```
POST /plans/{id}/incidents
  └ tx1: insert incident (idempotent) + audit + publish IncidentCreatedEvent
AFTER_COMMIT ─▶ WorkflowRunner (REQUIRES_NEW tx)
  └ lock incident row → lock team row → pick responder → supersede old assignment → insert assignment
    → guarded UPDATE incident → notification row (dedupe key) → audit
After commit ─▶ NotificationDispatcher delivers IN_APP (failure never rolls back the assignment)
Scheduler (15 s): find ASSIGNED incidents past deadline → per incident, NEW tx: lock row, RE-READ, escalate only if still due
Scheduler (30 s): recovery sweep for incidents committed but never processed (crash between commit and handler)
```
Why no outbox: the recovery sweep gives the same crash guarantee for an MVP with far less machinery. It is a documented trade-off (DECISIONS D-014).

## Concurrency model
1. The incident row lock (`SELECT … FOR UPDATE`) is the single serialisation point for every change to one incident (human actions and scheduler alike). State is re-read **after** the lock.
2. Guarded `UPDATE … WHERE status = … / current_sequence = …` returns 0 rows when stale → 409, never a silent overwrite.
3. The team row lock serialises auto-assignment so concurrent incidents spread across responders.
4. DB constraints are the last line of defence (one open assignment, unique `(incident, sequence)`, unique notification key, unique idempotency key).

## Security
Default-deny URL rules; `@PreAuthorize` on admin APIs; plan-scoped endpoints authorize through `AccessService`; VIEWER is read-only globally; 404 for unreadable resources; BCrypt; generic login failure; no secrets in the frontend bundle (only `VITE_*` is exposed).
