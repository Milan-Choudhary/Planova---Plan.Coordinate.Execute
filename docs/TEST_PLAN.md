# TEST PLAN

| Layer | What | Count | Status |
|---|---|---|---|
| Schema | `scripts/verify-migrations.sh` – V1–V5 + 28 assertions on real PG16 | 28 | **Run, passing** |
| SQL / concurrency | `scripts/verify-incident-sql.sh` – every `IncidentSql` statement, 6 race scenarios, concurrent connections | 76 | **Run, passing** |
| Domain (pure Java) | `IncidentDomainTest`, `PolicyAndTaskStatusTest` | 25 | **Run (minimal JUnit shim), passing** |
| Frontend | vitest + Testing Library | 35 | **Run, passing** |
| Backend integration | `AuthIntegrationTest`, `SecurityDefaultsIntegrationTest`, `SecurityPropertiesTest`, `PlanningIntegrationTest`, `IncidentWorkflowIntegrationTest` (Testcontainers, MockMvc, movable `Clock`) | 49 | **Written, NOT RUN** (no Maven Central / Docker in the authoring sandbox) |
| End-to-end through browser | – | 0 | Not run; manual script below |

Run on a real machine: `cd backend && mvn verify` (Docker running).

## Manual acceptance script (maps to the brief's acceptance test)
1. `SETUP.md` steps 1–4. Log in as `organizer@planova.local`.
2. Open *Demo Summer Fest* → add an event and a task assigned to the demo member.
3. Report an incident “Stage power outage” → it is persisted and auto-assigned to the demo member (first in chain); severity raised to HIGH by keyword.
4. Log in as `member@planova.local` → Notifications shows the assignment → open it → Acknowledge → Resolve.
5. Report another; do **not** acknowledge; after the deadline (5/15/30/60 min by severity; shorten the definition for a demo) the second responder gets it (“escalated ×1”).
6. Resolve a third before its deadline: it is never escalated.
7. As `viewer@planova.local`, `curl` a POST to `/api/v1/plans/{id}/tasks` with the viewer's token → 403. As admin open *Audit*.
