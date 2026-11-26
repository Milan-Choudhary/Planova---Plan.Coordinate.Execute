# Team Leads inherited plan Editor access

## Expected permissions

- **Account role** comes from sign-up or account administration. An Organizer can create event plans and teams.
- **Plan role** applies to one event plan. Its creator becomes the Owner. An Editor receives plan-wide editing rights through an explicit grant.
- **Team role** applies to a responder team. A Team Lead can read plans linked to their team and handle assigned work. An escalation position determines when they receive an incident.

## What happened

The backend treated an Organizer who was a Lead on the plan's responder team as an Editor when they had no explicit plan membership. It generated the Editor role during the access check, so the plan owner did not have to grant it.

For example, a venue Team Lead on a wedding plan could edit the schedule and manage plan-wide tasks and incidents as soon as they joined the responder team as a Lead. Owner-only actions such as managing plan membership remained restricted. The finding concerned plans linked to that team.

The teammate's security review rated this **Medium**. The effective permissions exceeded the documented plan membership rules and the authority the owner had explicitly delegated.
