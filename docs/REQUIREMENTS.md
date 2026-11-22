# REQUIREMENTS

Source of truth: `PLANOVA_AI_MASTER_BUILD_BRIEF.md` and the project specification. This file lists what the MVP does, who may do it, and what is deliberately **not** done.

## Roles
| Role | Can |
|---|---|
| ADMIN | Everything: users, teams, all plans, audit log, manual assignment of any incident |
| ORGANIZER | Create plans; edit plans they own or are an EDITOR of; assign tasks/incidents in those plans |
| TEAM_MEMBER | Work on assigned tasks (status only), report incidents, acknowledge/resolve incidents assigned to them |
| VIEWER | Read-only everywhere, whatever plan membership they hold |

Per-plan role (`plan_member`): OWNER, EDITOR, VIEWER. A team member of the plan's team may **read** the plan (so responders see what they must handle). A plan the caller cannot read answers **404**, not 403.

## Functional requirements (all implemented, see PROGRESS.md for verification status)
| ID | Requirement |
|---|---|
| FR-1 | Login with email + password; JWT; role and status re-read from DB per request |
| FR-2 | Admin manages users (create, change role, disable) |
| FR-3 | Admin manages teams: members, availability, escalation chain (positions 1..n) |
| FR-4 | Organizer creates plans (DRAFT → ACTIVE → ARCHIVED), events, tasks; plan members and roles |
| FR-5 | Tasks: assignee, priority, deadline, status machine; overdue flag |
| FR-6 | Any non-viewer who can read a plan reports an incident (title, description, optional severity) |
| FR-7 | Severity can only be **raised** by keyword rules, never lowered |
| FR-8 | Automatic assignment to the eligible responder with the lowest open load (ties: chain order) |
| FR-9 | Responder acknowledges, rejects (passes on), starts, resolves; manager/reporter closes and reopens |
| FR-10 | Overdue acknowledgement escalates to the next responder in the chain (bounded by `maxEscalationLevel`) |
| FR-11 | Resolving/acknowledging first prevents any later escalation |
| FR-12 | In-app notifications with deduplication and bounded retry |
| FR-13 | Repeated "report incident" requests with one `Idempotency-Key` create one incident |
| FR-14 | Append-only audit trail of every business change, readable per plan / per incident / globally (admin) |
| FR-15 | Dashboard numbers scoped to the caller |
| FR-16 | No eligible responder / chain exhausted → administrators notified; manual assignment possible |

## Non-functional requirements
- Credentials only in `.env`; `application.yml` and `docker-compose.yml` hold `${VAR}` references.
- PostgreSQL runs from a Docker image. Flyway owns the schema.
- Every state change guarded by the database (see DATA_MODEL.md), not only by service code.
- Standard JSON error shape with a correlation id.

## Explicit non-goals / deferred
Email notifications (only IN_APP is real; the `EMAIL` channel value exists in the schema but nothing sends mail), reminders for overdue tasks, workflow-definition editing (view only), login rate limiting, refresh tokens, optional AI features (Phase 8), horizontal scaling (the scheduler assumes one backend instance; row locks keep two instances correct but not efficient).
