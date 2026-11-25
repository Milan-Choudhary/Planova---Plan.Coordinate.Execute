# Reception simulation from scratch

## 1. Why is Demo Organizer also the Operations Response Lead?

**Demo Organizer holds two separate roles.** The demo setup made that account the **Owner of Demo Summer Fest** and also added it to **Operations Response** as a **Team Lead at escalation position 3**. One role didn’t create the other.

- **Plan Owner** means they control that plan and can grant or revoke Editor access.
- **Team Lead** means they have a designated role on that team. **Position 3** puts them after the two responders in the escalation chain.

When you create your own plan, you become its **Plan Owner** automatically. To also appear in its team’s escalation chain, add your own account to the team and give it a position.

## 2. Build a Reception plan from scratch

### Step 1 — Sign up three fresh accounts

Open [Planova sign-up](http://localhost:5173/signup). Create these accounts, signing out between accounts if needed. If an address is already taken, add a suffix to it.

| Example email | Choose at sign-up | What you’ll assign later |
|---|---|---|
| `reception-owner@planova.local` | **Organizer** | Plan Owner, plus an optional spot in the escalation chain |
| `reception-venue-lead@planova.local` | **Organizer** | Team Lead, then Plan Editor during the test |
| `reception-responder@planova.local` | **Team Member** | Responder who receives the first assignment |

For this **local simulation**, manually enter the value of `SEED_PASSWORD` from your local `.env` in each sign-up form. Signup uses the password you enter. It doesn’t load the seed password automatically.

### Step 2 — Create the team and escalation chain

1. Sign in as `reception-owner@planova.local`.
2. Click **Teams → Create team**, enter `Reception Operations`, and submit.
3. Click **Reception Operations**. Under **Add or update team member**, enter each registered email, select its role and position, then click **Save team member**.

   | Position | Account | Team role |
   |---|---|---|
   | 1 | `reception-responder@planova.local` | **Responder** |
   | 2 | `reception-venue-lead@planova.local` | **Team lead** |
   | 3 | `reception-owner@planova.local` | **Team lead** |

   The last row is optional. Add it if you want the owner to receive an escalation after the venue lead. Both organizers can have the Team Lead label, but only the account that creates the plan becomes its Owner.

### Step 3 — Create and activate the plan

1. Still signed in as `reception-owner@planova.local`, click **Plans → Create plan**.
2. Enter `Reception` as the title and select **Reception Operations** as the responder team. Click **Create plan**.
3. Open **Overview → Activate**. The plan header should show your plan role as **OWNER**.
4. Open **Events → Add event**, enter `Guest arrival`, choose a start and end time, and save it.

### Step 4 — Prove the Editor permission works

1. Sign in as `reception-venue-lead@planova.local`. Open **Plans → Reception → Events**. You should see **Guest arrival**, with no **Add event** form.
2. As `reception-owner@planova.local`, open **Reception → Members → Grant plan Editor access**. Select the venue lead and click **Grant Editor access**.
3. Return as the venue lead and refresh the plan. In **Events**, use **Add event** to create `Venue walkthrough`.
4. As the owner, go to **Members** and click **Revoke Editor** beside the venue lead. Refresh as the venue lead. The lead can still see the plan through team membership, and the edit controls should disappear again.

### Step 5 — Try the escalation chain

1. As the owner, open **Reception → Incidents → Report an incident**. Report `Reception entrance lights are out`.
2. Open the incident and check the **Assignee**. For a fresh team with no existing incident workload, expect the first responder.
3. Sign in as `reception-responder@planova.local`. Open **Notifications**, click the assignment, and choose **Reject (pass on)** before acknowledging.
4. Sign in as `reception-venue-lead@planova.local`. Open **Notifications** and follow the new assignment. Click **Acknowledge → Start work → Resolve**. This should work even after the Editor grant has been revoked, because the incident is assigned to the lead.
5. As the owner, open the incident, review the resolution, and click **Close**. Check **Assignment history** and **Audit trail** to see the handoff.

**Verified** — The earlier live wedding demo confirmed that a Team Lead can read the plan, can edit after an explicit grant, and loses plan-wide editing after revocation while retaining assigned work.

**Not verified** — The instructions above describe a fresh setup teammates can run on their own local database.

**Next step** — Start with the three sign-ups, then create **Reception Operations** as the owner account.
