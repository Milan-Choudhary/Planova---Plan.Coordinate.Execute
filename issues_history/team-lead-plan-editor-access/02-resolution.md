# Explicit plan Editor delegation

## How it was fixed

The backend now reads the plan role from the plan membership record. A Team Lead can still open plans linked to their team and handle assigned tasks and incidents. Plan-wide edits require an explicit Owner or Editor role, with Platform Admin access handled separately.

The **Members** tab lets a Plan Owner or Platform Admin grant Editor access to an eligible Organizer associated with the plan or responder team. **Revoke Editor** removes the explicit plan membership. A person who still belongs to the responder team retains access through that membership. A grant applies to the whole plan. Department-specific editing remains outside this fix.

## Files updated for the fix

| File | Change |
| --- | --- |
| [AccessService.java](../../backend/src/main/java/com/planova/access/AccessService.java) | Removed the rule that supplied an implicit Editor role from Organizer and Team Lead status. |
| [PlanDetailPage.tsx](../../frontend/src/pages/PlanDetailPage.tsx) | Added the grant and revoke controls to the Members tab using the existing plan membership API. |
| [PlanningIntegrationTest.java](../../backend/src/test/java/com/planova/planning/PlanningIntegrationTest.java) | Added a regression test covering denial before delegation, successful edits after the grant, denied edits after revocation, and continued access to assigned work. |

## Verification

- **Automated checks** — 77 backend tests, 36 frontend tests, frontend type checking, and the production build passed.
- **Live API simulation** — A newly registered Venue Lead could read the demo plan and received HTTP 403 for a plan edit before delegation. After the Editor grant, the lead added an event. After revocation, plan edits returned HTTP 403 again and the lead moved their assigned task to **In progress**.
- **Team walkthrough** — [Reception simulation from scratch](03-reception-simulation.md).
