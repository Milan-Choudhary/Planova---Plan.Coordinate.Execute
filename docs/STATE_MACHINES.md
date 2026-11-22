# STATE MACHINES

## Incident
```
OPEN ──assign──▶ ASSIGNED ──ack──▶ ACKNOWLEDGED ──start──▶ IN_PROGRESS ──resolve──▶ RESOLVED ──close──▶ CLOSED
                    │  ▲                │                                              │                  │
                    │  └─ reassign / escalate (new assignment sequence, status stays ASSIGNED)             │
                    └────────────────── ack → resolve allowed directly ─────────▶       └──── reopen ──────┴─▶ OPEN (then auto-assigned)
```
| Action | Who | From → To |
|---|---|---|
| auto-assign / manual assign | system / admin or plan editor | OPEN, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS → ASSIGNED (new sequence) |
| acknowledge | current assignee | ASSIGNED → ACKNOWLEDGED |
| reject | current assignee | ASSIGNED → ASSIGNED (next responder, immediate) |
| start | current assignee | ACKNOWLEDGED → IN_PROGRESS |
| resolve | assignee, admin or plan editor | ACKNOWLEDGED/IN_PROGRESS → RESOLVED |
| close | reporter, admin or plan editor | RESOLVED → CLOSED |
| reopen | reporter, admin or plan editor | RESOLVED/CLOSED → OPEN → auto-assign |
| escalate | system (deadline) | ASSIGNED → ASSIGNED (next responder), only if still overdue **under the lock** |

Repeating an action that already took effect (ack on ACKNOWLEDGED, resolve on RESOLVED…) returns the current state and changes nothing. Reassign/escalate are not status changes (D-013): they create assignment #n+1 while the incident stays ASSIGNED, so "who has it now" is always the single open assignment.

## Workflow execution
`PENDING → WAITING_ACK → COMPLETED` (resolved) or `FAILED` (`NO_ELIGIBLE_RESPONDER`, `ESCALATION_EXHAUSTED`, or retries used up). FAILED executions are never rescanned; an administrator is notified and must assign manually.

## Notification
`PENDING → SENT` or `PENDING → FAILED` (retried up to 3 attempts, then stays FAILED and visible).

## Task
`TODO ⇄ IN_PROGRESS → DONE → IN_PROGRESS`; `TODO/IN_PROGRESS → CANCELLED → TODO`.

## Plan
`DRAFT → ACTIVE → ARCHIVED → ACTIVE`. Archiving is refused (409 `OPEN_INCIDENTS`) while incidents are unresolved. ARCHIVED is read-only for everybody.
