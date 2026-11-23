# EDGE CASES

| # | Case | Behaviour | Where proven |
|---|---|---|---|
| 1 | Ack and escalation race on one incident | Row lock: one wins; loser re-reads and no-ops | SQL verifier (40 runs); `IncidentWorkflowIntegrationTest` (not run) |
| 2 | Three workers escalate the same incident | Exactly one new assignment | SQL verifier (30 runs) |
| 3 | Resolve before the deadline, then the sweep runs | Not escalated (status ≠ ASSIGNED, execution COMPLETED) | SQL verifier, integration test (not run) |
| 4 | Ex-assignee acknowledges after escalation | 409 `NOT_CURRENT_ASSIGNEE` | integration test (not run) |
| 5 | Same report sent twice / concurrently | One incident (201 then 200) | SQL verifier (20 concurrent) |
| 6 | Duplicate `IncidentCreatedEvent` | One assignment, one notification | SQL verifier |
| 7 | Process dies after commit, before handler | Recovery sweep processes it | SQL (`NEEDS_WORKFLOW`) |
| 8 | No eligible responder | Incident OPEN, admins notified, manual assign works | SQL + integration test |
| 9 | Chain exhausted / reject at last responder | Execution FAILED + admins notified; reject → 409 `NO_NEXT_RESPONDER` | SQL + integration test |
| 10 | Responder unavailable / disabled | Excluded from selection | SQL |
| 11 | Notification delivery fails | Assignment stays committed; bounded retries; visible as FAILED | SQL |
| 12 | Two incidents for one team at once | Team lock spreads load | SQL (20 runs) |
| 13 | Stale client acts on old data | `expectedVersion` mismatch → 409 `STALE_VERSION`; UI re-reads | frontend test |
| 14 | Guess another plan's / incident's id | 404 | integration tests (not run) |
| 15 | VIEWER calls a write endpoint directly | 403 | integration tests (not run) |
| 16 | Event of another plan on a task/incident | 400 + composite FK | schema assertions |
| 17 | Archive plan with open incidents | 409 `OPEN_INCIDENTS` | integration test (not run) |
| 18 | No ACTIVE workflow definition | Failures logged only (no execution row to count on) | known limit |
| 19 | Audit row edit/delete | Blocked by trigger | schema assertions |
| 20 | Two instances of the backend | Correct (row locks) but both run the scheduler | known limit |
