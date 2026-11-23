# DATA MODEL (Flyway V1–V5)

| Table | Purpose | Key constraints |
|---|---|---|
| `app_user` | Accounts | unique `lower(email)`; role/status CHECK |
| `team`, `team_membership` | Teams, availability, escalation position | unique `(team, escalation_order)`; order ≥ 1 |
| `plan`, `plan_member` | Plans and per-plan access list | status/visibility CHECK; `end_date ≥ start_date` |
| `plan_event` | Events | `end_at > start_at`; `unique(id, plan_id)` |
| `task` | Tasks | composite FK `(event_id, plan_id)` → event is always in the same plan |
| `incident` | Incidents | composite FK to event; **active states require an assignee**; ASSIGNED requires a deadline; unique `(reporter, idempotency_key)` |
| `incident_assignment` | Assignment history | **partial unique index: one open assignment per incident**; `unique(incident, sequence)` |
| `workflow_definition`, `workflow_execution` | Workflow config and per-incident run | one execution per incident; seeded `incident-default` v1 (V5) |
| `notification` | Inbox + delivery state | unique dedupe key per recipient/channel; attempts bounded in code |
| `audit_record` | Append-only history | trigger blocks UPDATE / DELETE / TRUNCATE; no FK to users |

Conventions: UUID keys, `TIMESTAMPTZ` (UTC), enum-like `VARCHAR + CHECK`, `version` columns for optimistic checks.

Workflow definition (V5 seed): `{"ackTimeoutMinutes":{"CRITICAL":5,"HIGH":15,"MEDIUM":30,"LOW":60},"maxEscalationLevel":3}`.
28 schema assertions in `scripts/schema-assertions.sql` prove each constraint accepts the good case and rejects the bad one.
