# Security Review

## Scope

Static review of authorization and critical incident workflows in the current
source tree. No attack payloads were executed, and no files were changed as
part of the review itself.

## Findings

| # | Severity | File | Lines | Vulnerability | Confidence |
|---|----------|------|-------|---------------|------------|
| 1 | MEDIUM | `backend/src/main/java/com/planova/access/AccessService.java` | 49-52, 70-72 | An `ORGANIZER` who is a `LEAD` of the plan's team is implicitly granted the plan role `EDITOR`, even without an `EDITOR` or `OWNER` entry in `plan_member`. `canEdit` accepts that synthesized role, granting edit and management permissions for plan-scoped operations. This conflicts with `docs/REQUIREMENTS.md`, which allows organizers to edit only plans they own or are explicitly an editor of. | 9/10 |

### Recommended remediation

Do not infer plan edit rights from team-lead membership. Grant edit access
through explicit plan membership, or change the documented permission policy
if team leads are intentionally meant to edit plans.

## Verification notes

`PROGRESS.md` reports that 74 Maven tests, including 45 PostgreSQL integration
tests, passed in the November 22, 2026 workspace check. `docs/TEST_PLAN.md`
still labels the backend integration tests as not run, so that status should
be reconciled. This review did not rerun the test suites.
