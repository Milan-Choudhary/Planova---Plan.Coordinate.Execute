# API CONTRACTS (Phases 2–6)

Base path `/api/v1`. JSON. Auth: `Authorization: Bearer <token>` unless noted.
This contract describes the V1–V5 source snapshot. Email preferences, workflow updates and severity suggestions have no endpoints in this snapshot. See D-023–D-025 for the planned designs.
Backend DTOs: `identity/IdentityDtos.java`, `planning/PlanningDtos.java`, `incident/IncidentDtos.java` · Frontend types: `frontend/src/api/types.ts`. Change both together.

## Error shape (every failure)
```json
{ "timestamp": "...", "status": 400, "code": "VALIDATION_FAILED", "message": "Request validation failed",
  "path": "/api/v1/admin/users", "correlationId": "…",
  "fieldErrors": [ { "field": "email", "message": "must be a well-formed email address" } ] }
```
Clients switch on `code`. The `X-Correlation-Id` response header equals `correlationId`.

| HTTP | code | meaning |
|---|---|---|
| 400 | VALIDATION_FAILED / MALFORMED_REQUEST / INVALID_PARAMETER / EMPTY_UPDATE / PASSWORD_TOO_LONG | bad input |
| 401 | AUTHENTICATION_REQUIRED | missing/invalid/expired token, or user disabled |
| 401 | INVALID_CREDENTIALS | login failed (same message for every cause) |
| 403 | FORBIDDEN | authenticated but role not allowed |
| 404 | NOT_FOUND / USER_NOT_FOUND / PLAN_NOT_FOUND / INCIDENT_NOT_FOUND / TASK_NOT_FOUND / EVENT_NOT_FOUND / TEAM_NOT_FOUND | unreadable resources are 404, not 403 |
| 409 | EMAIL_TAKEN / CANNOT_MODIFY_SELF / LAST_ADMIN / STALE_VERSION / CONFLICT / INVALID_TRANSITION / NOT_CURRENT_ASSIGNEE / NO_NEXT_RESPONDER / ALREADY_ASSIGNED / PLAN_ARCHIVED / OPEN_INCIDENTS / EVENT_IN_USE / ORDER_TAKEN / TEAM_NAME_TAKEN / OWNER_FIXED | state conflict |
| 500 | INTERNAL_ERROR | quote correlationId |

## Endpoints
| Method & path | Role | Request | Success |
|---|---|---|---|
| `POST /auth/login` (public) | – | `{email, password}` | 200 `{accessToken, tokenType:"Bearer", expiresInSeconds, user}` |
| `POST /auth/register` (public) | – | `{name, email, password, role?}`. Choose `ORGANIZER` or `TEAM_MEMBER`. Omitting the role gives `VIEWER`. | 201 `User`. Sign in to obtain a JWT. |
| `GET /auth/me` | any | – | 200 `User` |
| `GET /admin/users?page=0&size=20` | ADMIN | size clamped 1–100 | 200 `{items, page, size, totalItems, totalPages}` |
| `POST /admin/users` | ADMIN | `{email, name, role, password(12–72)}` | 201 + `Location`, `User` |
| `PATCH /admin/users/{id}` | ADMIN | `{role?, status?}` (≥1) | 200 `User` |

`User = {id, email, name, role: ADMIN|ORGANIZER|TEAM_MEMBER|VIEWER, status: ACTIVE|DISABLED}`

Other: `GET /actuator/health` (public), Swagger UI `/swagger-ui.html`.

## Teams
| Method & path | Who | Notes |
|---|---|---|
| `GET /teams` | any | admins/organizers see all, others their own |
| `GET /teams/{id}` | any (members, organizers, admin) | members ordered by `escalationOrder` |
| `POST /teams` | ADMIN | `{name, description?}` → 201 |
| `POST /teams/{id}/members` | ADMIN | upsert `{userId, teamRole: LEAD|RESPONDER|MEMBER, available?, escalationOrder?}` |
| `DELETE /teams/{id}/members/{userId}` | ADMIN | |
| `PATCH /teams/{id}/members/{userId}/availability` | ADMIN or that member | `{available}` |

## Plans, members, events
| Method & path | Who | Notes |
|---|---|---|
| `POST /plans` | ADMIN, ORGANIZER | `{title, description?, teamId?, startDate?, endDate?, visibility?}` → 201; creator becomes OWNER |
| `GET /plans?status&q&page&size` | any | only plans the caller can read |
| `GET /plans/{id}` | readers | includes `myPlanRole, canEdit, canManage`, counts |
| `PATCH /plans/{id}` | editors | partial; team change only by owner; optional `expectedVersion` |
| `POST /plans/{id}/status` | owner/admin | `{status: DRAFT|ACTIVE|ARCHIVED}`; archive blocked while incidents unresolved |
| `GET /plans/{id}/members`, `PUT …/members`, `DELETE …/members/{userId}` | readers / owner | `PUT {userId, planRole: EDITOR|VIEWER}` |
| `GET/POST /plans/{id}/events`, `PUT/DELETE /plans/{id}/events/{eventId}` | readers / editors | `endAt` must be after `startAt`; delete refused while referenced |

## Tasks
| Method & path | Who | Notes |
|---|---|---|
| `POST /plans/{id}/tasks` | editors | `{title, description?, eventId?, assigneeId?, priority?, deadline?}`; assignee must be eligible |
| `GET /plans/{id}/tasks?status&assigneeId` | readers | |
| `GET /tasks/mine?openOnly=true` | any | |
| `GET /tasks/{id}` | readers | |
| `PATCH /tasks/{id}` | editors (all fields) / assignee (`status` only) | transitions per STATE_MACHINES.md; `expectedVersion` optional |

## Incidents
| Method & path | Who | Notes |
|---|---|---|
| `POST /plans/{id}/incidents` | non-viewer plan readers | header `Idempotency-Key` (≤100 chars, optional). **201** new, **200** replay of the same key |
| `GET /incidents?planId&status&assignedToMe&unresolvedOnly&page&size` | any | visible plans only |
| `GET /incidents/{id}` | readers | includes `allowedActions`, `ackOverdue`, `escalationLevel`, `version` |
| `GET /incidents/{id}/history` | readers | `{assignments[], audit[]}` |
| `POST /incidents/{id}/assign` | admin / plan editor | `{assigneeId, note?}` |
| `POST /incidents/{id}/acknowledge \| reject \| start \| resolve \| close \| reopen` | see STATE_MACHINES.md | optional body `{expectedVersion?, note?}` |

`allowedActions ⊆ {ACKNOWLEDGE, REJECT, START, RESOLVE, ASSIGN, CLOSE, REOPEN}`.

## Notifications, dashboard, audit, admin
| Method & path | Who | Notes |
|---|---|---|
| `GET /notifications?unreadOnly&page&size`, `GET /notifications/unread-count`, `POST /notifications/{id}/read`, `POST /notifications/read-all` | owner of the inbox | always scoped to the caller |
| `GET /dashboard` | any | `{plans, openIncidents, myAssignedIncidents, overdueAcknowledgements, myOpenTasks, myOverdueTasks, upcomingEvents, unreadNotifications}` |
| `GET /plans/{id}/audit?action&page&size` | readers | newest first |
| `GET /admin/audit?action&page&size` | ADMIN | everything |
| `GET /admin/workflow` | ADMIN | active definition (view only) |
