"""
Executes the REAL incident SQL (parsed out of IncidentSql.java) against PostgreSQL, including
concurrent-connection race tests for the scenarios in the master brief:

  - repeated report requests / duplicate events        - ack arriving as the escalation deadline passes
  - two workers escalating the same incident           - incident resolved before escalation
  - two incidents competing for the same responder     - notification duplicates, failures, retry limits
  - invalid / stale transitions                        - crash recovery sweep

SCOPE, stated honestly: the orchestration functions below (assign_internal, escalate_one, ...) mirror
the order of statements in the Java services (IncidentService / WorkflowService / EscalationService).
This proves the SQL and the locking strategy are correct; it does NOT prove the Java glue compiles.
Usage: python3 -I verify_incident_sql.py <IncidentSql.java> "<libpq conninfo>"
"""
import datetime as dt
import re
import sys
import threading
import uuid

import psycopg
from psycopg.rows import dict_row

SRC, CONNINFO = sys.argv[1], sys.argv[2]

SQL = {
    name: text.replace("?", "%s")
    for name, text in re.findall(r'public static final String (\w+) = """\n(.*?)""";', open(SRC).read(), re.S)
}
EXPECTED = 26
if len(SQL) < EXPECTED:
    sys.exit(f"parsed only {len(SQL)} SQL constants from {SRC}, expected >= {EXPECTED}")

TIMEOUT_MIN = {"CRITICAL": 5, "HIGH": 15, "MEDIUM": 30, "LOW": 60}   # = V5 seed
MAX_ESCALATION = 3
UTC = dt.timezone.utc

results = {"pass": 0, "fail": 0}


def check(cond, label):
    if cond:
        results["pass"] += 1
        print(f"PASS  {label}")
    else:
        results["fail"] += 1
        print(f"FAIL  {label}")


def connect():
    return psycopg.connect(CONNINFO, row_factory=dict_row)


def now():
    return dt.datetime.now(UTC)


def one(cur, name, params=()):
    cur.execute(SQL[name], params)
    return cur.fetchone()


def run(cur, name, params=()):
    cur.execute(SQL[name], params)
    return cur.rowcount


# ------------------------------------------------------------------ fixtures

def new_world(n_responders=3, available=None):
    """A fresh team/plan/users so scenarios cannot influence each other."""
    w = {"team": uuid.uuid4(), "plan": uuid.uuid4(), "reporter": uuid.uuid4(), "responders": []}
    with connect() as c, c.cursor() as cur:
        def user(role):
            uid = uuid.uuid4()
            cur.execute("INSERT INTO app_user (id,email,name,password_hash,role) VALUES (%s,%s,'t','x',%s)",
                        (uid, f"{uid}@t.test", role))
            return uid
        w["reporter"] = user("TEAM_MEMBER")
        w["admin"] = user("ADMIN")
        cur.execute("INSERT INTO team (id,name) VALUES (%s,%s)", (w["team"], f"team-{w['team']}"))
        for i in range(n_responders):
            uid = user("TEAM_MEMBER")
            w["responders"].append(uid)
            avail = True if available is None else available[i]
            cur.execute("INSERT INTO team_membership (team_id,user_id,team_role,available,escalation_order) "
                        "VALUES (%s,%s,'RESPONDER',%s,%s)", (w["team"], uid, avail, i + 1))
        cur.execute("INSERT INTO plan (id,title,owner_id,team_id) VALUES (%s,'p',%s,%s)",
                    (w["plan"], w["reporter"], w["team"]))
        c.commit()
    return w


def report(conn, w, key=None, severity="HIGH", title="Wi-Fi down"):
    """Mirror of IncidentService.report: idempotent insert, then (in Java) audit + after-commit event."""
    cur = conn.cursor()
    iid, t = uuid.uuid4(), now()
    if run(cur, "INSERT_INCIDENT", (iid, w["plan"], None, title, "desc", severity, w["reporter"], key, t, t)) == 0:
        iid = one(cur, "FIND_BY_IDEMPOTENCY", (w["reporter"], key))["id"]
        created = False
    else:
        created = True
    conn.commit()
    return iid, created


def incident(cur, iid):
    return one(cur, "SELECT_INCIDENT", (iid,))


def open_assignments(cur, iid):
    cur.execute("SELECT count(*) AS n FROM incident_assignment WHERE incident_id=%s "
                "AND status IN ('ACTIVE','ACKNOWLEDGED')", (iid,))
    return cur.fetchone()["n"]


def max_seq(cur, iid):
    cur.execute("SELECT coalesce(max(sequence),0) AS m FROM incident_assignment WHERE incident_id=%s", (iid,))
    return cur.fetchone()["m"]


def exec_status(cur, iid):
    cur.execute("SELECT status, attempts FROM workflow_execution WHERE incident_id=%s", (iid,))
    return cur.fetchone()


# ------------------------------------------------------------------ mirrors of the Java services

def pick_initial(cands):                       # = ResponderSelector.pickInitial
    return min(cands, key=lambda c: (c["open_load"], c["escalation_order"]), default=None)


def pick_next(cands, after, excluded):         # = ResponderSelector.pickNext
    ok = [c for c in cands if c["escalation_order"] > after and c["user_id"] != excluded]
    return min(ok, key=lambda c: c["escalation_order"], default=None)


def assign_internal(cur, inc, assignee, by, reason, t):
    """Order matters: supersede FIRST, then insert (D-008), then the guarded incident update."""
    seq = inc["current_sequence"] + 1
    deadline = t + dt.timedelta(minutes=TIMEOUT_MIN[inc["severity"]])
    run(cur, "SUPERSEDE_OPEN_ASSIGNMENTS", (t, inc["id"]))
    run(cur, "INSERT_ASSIGNMENT", (uuid.uuid4(), inc["id"], seq, assignee, reason, by, t, deadline))
    n = run(cur, "SET_ASSIGNED", (assignee, deadline, seq, t, inc["id"], inc["current_sequence"]))
    if n != 1:
        raise RuntimeError("concurrent assignment detected")
    run(cur, "SET_EXECUTION_STATUS", ("WAITING_ACK", None, None, t, inc["id"]))
    notify(cur, assignee, inc["id"], "INCIDENT_ASSIGNED", f"INCIDENT_ASSIGNED:{inc['id']}:{seq}", t)
    return seq


def notify(cur, recipient, iid, ntype, key, t):
    return run(cur, "INSERT_NOTIFICATION", (uuid.uuid4(), recipient, iid, ntype, "title", "body", key, t))


def auto_assign_in_tx(cur, iid, t):
    inc = one(cur, "LOCK_INCIDENT", (iid,))
    if inc["status"] != "OPEN":
        return "NOOP"
    definition = one(cur, "SELECT_ACTIVE_DEFINITION")
    run(cur, "UPSERT_EXECUTION", (uuid.uuid4(), iid, definition["id"], t, t))
    team = one(cur, "PLAN_TEAM", (inc["plan_id"],))["team_id"]
    if team is not None:
        run(cur, "LOCK_TEAM", (team,))
        cur.execute(SQL["TEAM_CANDIDATES"], (team,))
        cands = cur.fetchall()
    else:
        cands = []
    pick = pick_initial(cands)
    if pick is None:
        run(cur, "SET_EXECUTION_STATUS", ("FAILED", "NO_ELIGIBLE_RESPONDER", t, t, iid))
        return "NO_RESPONDER"
    assign_internal(cur, inc, pick["user_id"], None, "AUTO", t)
    return pick["user_id"]


def auto_assign(conn, iid, t=None):
    with conn.cursor() as cur:
        out = auto_assign_in_tx(cur, iid, t or now())
    conn.commit()
    return out


def acknowledge(conn, iid, actor, t=None):
    t = t or now()
    with conn.cursor() as cur:
        inc = one(cur, "LOCK_INCIDENT", (iid,))
        if inc["status"] == "ACKNOWLEDGED" and inc["assignee_id"] == actor:
            conn.commit()
            return "ALREADY"                       # repeated request: no change, no error
        if inc["assignee_id"] != actor:
            conn.rollback()
            return "NOT_CURRENT_ASSIGNEE"
        if inc["status"] != "ASSIGNED":
            conn.rollback()
            return "WRONG_STATE"
        assert run(cur, "ACK_ASSIGNMENT", (t, iid, inc["current_sequence"])) == 1
        assert run(cur, "SET_ACKNOWLEDGED", (t, t, iid)) == 1
        run(cur, "SET_EXECUTION_STATUS", ("COMPLETED", None, t, t, iid))
    conn.commit()
    return "OK"


def escalate_one(conn, iid, t, force=False):
    """Mirror of EscalationService.escalateOne: re-read FRESH state under the row lock, then decide."""
    with conn.cursor() as cur:
        inc = one(cur, "LOCK_INCIDENT", (iid,))
        if inc["status"] != "ASSIGNED":
            conn.rollback()
            return "SKIPPED_STATE"
        if not force and inc["ack_deadline"] > t:
            conn.rollback()
            return "SKIPPED_NOT_DUE"
        team = one(cur, "PLAN_TEAM", (inc["plan_id"],))["team_id"]
        cands = []
        if team is not None and inc["current_sequence"] - 1 < MAX_ESCALATION:
            cur.execute(SQL["TEAM_CANDIDATES"], (team,))
            cands = cur.fetchall()
        pos_row = one(cur, "CHAIN_POSITION", (team, inc["assignee_id"])) if team else None
        after = pos_row["escalation_order"] if pos_row and pos_row["escalation_order"] else 0
        nxt = pick_next(cands, after, inc["assignee_id"])
        if nxt is None:
            run(cur, "SET_EXECUTION_STATUS", ("FAILED", "ESCALATION_EXHAUSTED", t, t, iid))
            conn.commit()
            return "EXHAUSTED"
        assign_internal(cur, inc, nxt["user_id"], None, "ESCALATION", t)
    conn.commit()
    return "ESCALATED"


def start_work(conn, iid, actor, t):
    with conn.cursor() as cur:
        inc = one(cur, "LOCK_INCIDENT", (iid,))
        if inc["assignee_id"] != actor or inc["status"] != "ACKNOWLEDGED":
            conn.rollback()
            return "REJECTED"
        run(cur, "SET_IN_PROGRESS", (t, iid))
    conn.commit()
    return "OK"


def resolve(conn, iid, actor, t):
    with conn.cursor() as cur:
        inc = one(cur, "LOCK_INCIDENT", (iid,))
        if inc["assignee_id"] != actor or inc["status"] not in ("ACKNOWLEDGED", "IN_PROGRESS"):
            conn.rollback()
            return "REJECTED"
        assert run(cur, "SET_RESOLVED", (t, "fixed", t, iid)) == 1
        run(cur, "COMPLETE_ASSIGNMENT", (t, iid))
        run(cur, "SET_EXECUTION_STATUS", ("COMPLETED", None, t, t, iid))
    conn.commit()
    return "OK"


def close(conn, iid, t):
    with conn.cursor() as cur:
        one(cur, "LOCK_INCIDENT", (iid,))
        n = run(cur, "SET_CLOSED", (t, t, iid))
    conn.commit()
    return n


def reopen(conn, iid, t):
    with conn.cursor() as cur:
        one(cur, "LOCK_INCIDENT", (iid,))
        if run(cur, "SET_REOPENED", (t, iid)) != 1:
            conn.rollback()
            return "REJECTED"
        out = auto_assign_in_tx(cur, iid, t)
    conn.commit()
    return out


def in_threads(*fns):
    """Runs callables truly concurrently (barrier-aligned), each on its own connection."""
    barrier = threading.Barrier(len(fns))
    out = [None] * len(fns)

    def wrap(i, fn):
        conn = connect()
        try:
            barrier.wait()
            out[i] = fn(conn)
        except Exception as e:                       # noqa: BLE001 - surfaced to the assertions
            out[i] = f"EXC:{type(e).__name__}:{e}"
        finally:
            conn.close()
    ts = [threading.Thread(target=wrap, args=(i, f)) for i, f in enumerate(fns)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    return out


# ================================================================== scenarios

def s_happy_path():
    w = new_world()
    r1, r2, r3 = w["responders"]
    with connect() as c:
        iid, created = report(c, w, key="k1")
        iid2, created2 = report(c, w, key="k1")
        check(created and not created2 and iid == iid2, "report: same Idempotency-Key twice -> one incident, same id")
        with c.cursor() as cur:
            check(incident(cur, iid)["status"] == "OPEN", "report: new incident starts OPEN")
        who = auto_assign(c, iid)
        check(who == r1, "auto-assign: first responder chosen when all loads are equal")
        again = auto_assign(c, iid)
        check(again == "NOOP", "auto-assign: duplicate INCIDENT_CREATED event is a no-op (incident no longer OPEN)")
        with c.cursor() as cur:
            inc = incident(cur, iid)
            check(inc["status"] == "ASSIGNED" and inc["assignee_id"] == r1 and inc["current_sequence"] == 1,
                  "assigned: ASSIGNED, assignee set, sequence 1")
            check(inc["ack_deadline"] is not None, "assigned: ack deadline stored (HIGH = 15 min)")
            check(exec_status(cur, iid)["status"] == "WAITING_ACK", "workflow execution is WAITING_ACK")
            check(open_assignments(cur, iid) == 1, "exactly one open assignment")
        check(acknowledge(c, iid, r2) == "NOT_CURRENT_ASSIGNEE", "ack by someone who is not the assignee is refused")
        check(acknowledge(c, iid, r1) == "OK", "ack by the assignee succeeds")
        check(acknowledge(c, iid, r1) == "ALREADY", "ack repeated -> idempotent, no error, no change")
        with c.cursor() as cur:
            v_before = incident(cur, iid)["version"]
        acknowledge(c, iid, r1)
        with c.cursor() as cur:
            check(incident(cur, iid)["version"] == v_before, "repeated ack does not bump the version")
            check(exec_status(cur, iid)["status"] == "COMPLETED", "ack completes the workflow execution")
        check(resolve(c, iid, r2, now()) == "REJECTED", "resolve by a non-assignee is refused")
        check(start_work(c, iid, r1, now()) == "OK", "start work: ACKNOWLEDGED -> IN_PROGRESS")
        check(resolve(c, iid, r1, now()) == "OK", "resolve: IN_PROGRESS -> RESOLVED")
        with c.cursor() as cur:
            inc = incident(cur, iid)
            check(inc["status"] == "RESOLVED" and inc["resolved_at"] is not None and inc["resolution_note"] == "fixed",
                  "resolved: timestamp and note stored")
            check(open_assignments(cur, iid) == 0, "resolved: assignment completed, none open")
        check(close(c, iid, now()) == 1, "close: RESOLVED -> CLOSED")
        check(reopen(c, iid, now()) != "REJECTED", "reopen: CLOSED -> OPEN -> auto-assigned again")
        with c.cursor() as cur:
            inc = incident(cur, iid)
            check(inc["status"] == "ASSIGNED" and inc["current_sequence"] == 2 and inc["closed_at"] is None,
                  "reopened: ASSIGNED with a NEW sequence (2), closed_at cleared")
            check(open_assignments(cur, iid) == 1, "reopened: exactly one open assignment")
            check(exec_status(cur, iid)["status"] == "WAITING_ACK", "reopened: execution restarted (WAITING_ACK)")


def s_invalid_transitions():
    w = new_world()
    r1 = w["responders"][0]
    with connect() as c:
        iid, _ = report(c, w)
        with c.cursor() as cur:
            check(run(cur, "SET_RESOLVED", (now(), "x", now(), iid)) == 0, "OPEN cannot jump to RESOLVED (0 rows)")
            check(run(cur, "SET_CLOSED", (now(), now(), iid)) == 0, "OPEN cannot jump to CLOSED (0 rows)")
            check(run(cur, "SET_ACKNOWLEDGED", (now(), now(), iid)) == 0, "OPEN cannot be acknowledged (0 rows)")
            check(run(cur, "SET_IN_PROGRESS", (now(), iid)) == 0, "OPEN cannot be started (0 rows)")
            check(run(cur, "SET_REOPENED", (now(), iid)) == 0, "OPEN cannot be reopened (0 rows)")
        c.rollback()
        auto_assign(c, iid)
        with c.cursor() as cur:
            check(run(cur, "SET_RESOLVED", (now(), "x", now(), iid)) == 0, "ASSIGNED cannot jump to RESOLVED (0 rows)")
            check(run(cur, "SET_IN_PROGRESS", (now(), iid)) == 0, "ASSIGNED cannot skip acknowledgement (0 rows)")
            inc = incident(cur, iid)
            n = run(cur, "SET_ASSIGNED", (r1, now(), 99, now(), iid, 42))   # stale expected sequence
            check(n == 0, "stale assignment (wrong expected sequence) updates nothing")
        c.rollback()


def s_escalation_chain():
    w = new_world(3)
    r1, r2, r3 = w["responders"]
    with connect() as c:
        iid, _ = report(c, w, severity="MEDIUM")
        auto_assign(c, iid)
        t0 = now()
        check(escalate_one(c, iid, t0) == "SKIPPED_NOT_DUE", "escalation before the deadline does nothing")
        late = t0 + dt.timedelta(minutes=31)           # MEDIUM = 30 min
        check(escalate_one(c, iid, late) == "ESCALATED", "deadline passed -> escalated")
        with c.cursor() as cur:
            inc = incident(cur, iid)
            check(inc["assignee_id"] == r2 and inc["current_sequence"] == 2, "escalated to the NEXT responder (position 2)")
            check(open_assignments(cur, iid) == 1, "after escalation still exactly one open assignment")
            cur.execute("SELECT status FROM incident_assignment WHERE incident_id=%s AND sequence=1", (iid,))
            check(cur.fetchone()["status"] == "SUPERSEDED", "previous assignment marked SUPERSEDED")
        check(escalate_one(c, iid, late) == "SKIPPED_NOT_DUE", "immediate re-run does not escalate again (new deadline)")
        check(acknowledge(c, iid, r1) == "NOT_CURRENT_ASSIGNEE", "ack by the OLD assignee after escalation is refused")
        later = late + dt.timedelta(minutes=31)
        check(escalate_one(c, iid, later) == "ESCALATED", "second missed deadline -> escalated to position 3")
        evenlater = later + dt.timedelta(minutes=31)
        check(escalate_one(c, iid, evenlater) == "EXHAUSTED", "end of chain -> EXHAUSTED (execution FAILED)")
        with c.cursor() as cur:
            check(exec_status(cur, iid)["status"] == "FAILED", "exhausted: workflow execution is FAILED")
            check(incident(cur, iid)["status"] == "ASSIGNED", "exhausted: incident stays ASSIGNED for a human to act")
            check(max_seq(cur, iid) == 3, "exactly 3 assignments in total, none duplicated")
        check(escalate_one(c, iid, evenlater + dt.timedelta(hours=5)) == "SKIPPED_STATE" or
              escalate_one(c, iid, evenlater + dt.timedelta(hours=5)) == "EXHAUSTED",
              "exhausted incident is not escalated forever")
        with c.cursor() as cur:
            cur.execute(SQL["DUE_FOR_ESCALATION"], (evenlater + dt.timedelta(days=1), 1000))
            check(iid not in [r["id"] for r in cur.fetchall()], "scanner no longer returns the exhausted incident")
        c.rollback()


def s_skips_unavailable():
    w = new_world(3, available=[True, False, True])
    r1, _r2, r3 = w["responders"]
    with connect() as c:
        iid, _ = report(c, w, severity="LOW")
        auto_assign(c, iid)
        escalate_one(c, iid, now() + dt.timedelta(minutes=61))
        with c.cursor() as cur:
            check(incident(cur, iid)["assignee_id"] == r3, "unavailable responder (position 2) is skipped")


def s_no_responder():
    w = new_world(2, available=[False, False])
    with connect() as c:
        iid, _ = report(c, w)
        check(auto_assign(c, iid) == "NO_RESPONDER", "no eligible responder -> NO_RESPONDER outcome")
        with c.cursor() as cur:
            check(incident(cur, iid)["status"] == "OPEN", "incident stays OPEN (not silently dropped)")
            check(exec_status(cur, iid)["status"] == "FAILED", "execution FAILED with a recorded reason")
        c.rollback()


def s_disabled_user_not_chosen():
    w = new_world(2)
    with connect() as c, c.cursor() as cur:
        cur.execute("UPDATE app_user SET status='DISABLED' WHERE id=%s", (w["responders"][0],))
        c.commit()
        iid, _ = report(c, w)
        check(auto_assign(c, iid) == w["responders"][1], "DISABLED user is never selected")


def s_resolve_before_escalation():
    w = new_world(2)
    r1 = w["responders"][0]
    with connect() as c:
        iid, _ = report(c, w)
        auto_assign(c, iid)
        acknowledge(c, iid, r1)
        resolve(c, iid, r1, now())
        res = escalate_one(c, iid, now() + dt.timedelta(hours=2))
        check(res == "SKIPPED_STATE", "resolved before the deadline -> obsolete escalation is skipped")
        with c.cursor() as cur:
            check(max_seq(cur, iid) == 1 and incident(cur, iid)["status"] == "RESOLVED", "no extra assignment created")
            cur.execute(SQL["DUE_FOR_ESCALATION"], (now() + dt.timedelta(days=1), 1000))
            check(iid not in [r["id"] for r in cur.fetchall()], "scanner does not list resolved incidents")
        c.rollback()


def s_scanner_filters():
    w = new_world(2)
    r1 = w["responders"][0]
    with connect() as c:
        ids = {}
        for name in ("due", "acked", "future"):
            ids[name], _ = report(c, w, severity="LOW")
            auto_assign(c, ids[name])
        with c.cursor() as cur:
            owner = incident(cur, ids["acked"])["assignee_id"]     # load-aware selection may pick anyone
        check(acknowledge(c, ids["acked"], owner) == "OK", "setup: acknowledged by its real assignee")
        probe = now() + dt.timedelta(minutes=61)
        with c.cursor() as cur:
            cur.execute(SQL["DUE_FOR_ESCALATION"], (probe, 10000))
            got = {r["id"] for r in cur.fetchall()}
            cur.execute(SQL["DUE_FOR_ESCALATION"], (now(), 10000))
            got_now = {r["id"] for r in cur.fetchall()}
        check(ids["due"] in got and ids["future"] in got, "scanner lists ASSIGNED incidents past their deadline")
        check(ids["acked"] not in got, "scanner skips acknowledged incidents")
        check(ids["due"] not in got_now, "scanner skips incidents whose deadline has not passed")
        c.rollback()


def s_sweeper():
    w = new_world(2)
    with connect() as c:
        iid, _ = report(c, w)
        cutoff = now() + dt.timedelta(seconds=1)
        with c.cursor() as cur:
            cur.execute(SQL["NEEDS_WORKFLOW"], (cutoff, cutoff, 1000))
            check(iid in [r["id"] for r in cur.fetchall()], "crash recovery: committed-but-unprocessed incident is found")
        auto_assign(c, iid)
        with c.cursor() as cur:
            cur.execute(SQL["NEEDS_WORKFLOW"], (cutoff, cutoff, 1000))
            check(iid not in [r["id"] for r in cur.fetchall()], "processed incident is no longer returned")
        # bounded retries
        iid2, _ = report(c, w)
        with c.cursor() as cur:
            d = one(cur, "SELECT_ACTIVE_DEFINITION")
            run(cur, "UPSERT_EXECUTION", (uuid.uuid4(), iid2, d["id"], now(), now()))
            run(cur, "RECORD_EXECUTION_FAILURE", ("boom", now(), now(), iid2))
            s1 = exec_status(cur, iid2)
            cur.execute(SQL["NEEDS_WORKFLOW"], (cutoff, now() + dt.timedelta(minutes=1), 1000))
            check(iid2 in [r["id"] for r in cur.fetchall()], "failed-once execution (attempts<max) is retried")
            run(cur, "RECORD_EXECUTION_FAILURE", ("boom", now(), now(), iid2))
            run(cur, "RECORD_EXECUTION_FAILURE", ("boom", now(), now(), iid2))
            run(cur, "RECORD_EXECUTION_FAILURE", ("boom", now(), now(), iid2))  # beyond the limit
            s3 = exec_status(cur, iid2)
            cur.execute(SQL["NEEDS_WORKFLOW"], (cutoff, now() + dt.timedelta(minutes=1), 1000))
            check(s1["status"] == "RUNNING" and s1["attempts"] == 1, "failure #1 recorded, still RUNNING")
            check(s3["status"] == "FAILED" and s3["attempts"] == 3, "retry limit reached -> FAILED, attempts capped at max")
            check(iid2 not in [r["id"] for r in cur.fetchall()], "FAILED execution is not retried again")
        c.commit()


def s_notifications():
    w = new_world(2)
    r1 = w["responders"][0]
    with connect() as c:
        iid, _ = report(c, w)
        with c.cursor() as cur:
            n1 = notify(cur, r1, iid, "INCIDENT_ASSIGNED", f"K:{iid}:1", now())
            n2 = notify(cur, r1, iid, "INCIDENT_ASSIGNED", f"K:{iid}:1", now())
            n3 = notify(cur, r1, iid, "INCIDENT_ASSIGNED", f"K:{iid}:2", now())
            check((n1, n2, n3) == (1, 0, 1), "duplicate notification (same dedupe key) is suppressed, a new key is allowed")
            cur.execute("SELECT id FROM notification WHERE recipient_id=%s ORDER BY created_at", (r1,))
            nid = cur.fetchone()["id"]
            check(run(cur, "MARK_NOTIFICATION_FAILED", ("smtp down", nid)) == 1, "delivery failure is recorded")
            cur.execute("SELECT status, attempts, last_error FROM notification WHERE id=%s", (nid,))
            row = cur.fetchone()
            check(row["status"] == "FAILED" and row["attempts"] == 1 and row["last_error"] == "smtp down",
                  "failed notification keeps status, attempts and the error text")
            check(one(cur, "SELECT_INCIDENT", (iid,))["status"] == "OPEN",
                  "a notification failure does not touch the incident")
            run(cur, "MARK_NOTIFICATION_FAILED", ("again", nid))
            run(cur, "MARK_NOTIFICATION_FAILED", ("again", nid))
            cur.execute(SQL["NOTIFICATIONS_TO_DELIVER"], (3, 1000))
            check(nid not in [r["id"] for r in cur.fetchall()], "retry limit (3 attempts) reached -> not selected again")
        c.commit()
        # SKIP LOCKED: two workers never pick the same row
        with c.cursor() as cur:
            notify(cur, r1, iid, "X", f"S:{iid}:a", now())
            notify(cur, r1, iid, "X", f"S:{iid}:b", now())
        c.commit()
        a, b = connect(), connect()
        try:
            ca, cb = a.cursor(), b.cursor()
            ca.execute(SQL["NOTIFICATIONS_TO_DELIVER"], (3, 1))
            got_a = {r["id"] for r in ca.fetchall()}
            cb.execute(SQL["NOTIFICATIONS_TO_DELIVER"], (3, 1))
            got_b = {r["id"] for r in cb.fetchall()}
            check(len(got_a) == 1 and len(got_b) == 1 and not (got_a & got_b),
                  "SKIP LOCKED: two concurrent retry workers receive different notifications")
            run(ca, "MARK_NOTIFICATION_SENT", (now(), next(iter(got_a))))
            check(run(ca, "MARK_NOTIFICATION_SENT", (now(), next(iter(got_a)))) == 0, "marking SENT twice changes nothing")
        finally:
            a.rollback(); b.rollback(); a.close(); b.close()


# ---------------------------------------------------------------- concurrency

def s_race_ack_vs_escalation(trials=40):
    outcomes = {"ack_wins": 0, "escalation_wins": 0}
    bad = 0
    for _ in range(trials):
        w = new_world(3)
        r1, r2, _ = w["responders"]
        with connect() as c:
            iid, _ = report(c, w, severity="CRITICAL")
            auto_assign(c, iid)
        due = now() + dt.timedelta(minutes=6)              # CRITICAL = 5 min -> escalation is due
        ack, esc = in_threads(lambda cn: acknowledge(cn, iid, r1), lambda cn: escalate_one(cn, iid, due))
        with connect() as c, c.cursor() as cur:
            inc = incident(cur, iid)
            opened = open_assignments(cur, iid)
        if ack == "OK" and esc == "SKIPPED_STATE" and inc["status"] == "ACKNOWLEDGED" \
                and inc["assignee_id"] == r1 and inc["current_sequence"] == 1 and opened == 1:
            outcomes["ack_wins"] += 1
        elif esc == "ESCALATED" and ack == "NOT_CURRENT_ASSIGNEE" and inc["status"] == "ASSIGNED" \
                and inc["assignee_id"] == r2 and inc["current_sequence"] == 2 and opened == 1:
            outcomes["escalation_wins"] += 1
        else:
            bad += 1
            print("   inconsistent:", ack, esc, inc["status"], inc["current_sequence"], opened)
    check(bad == 0, f"ack vs escalation x{trials}: always exactly one winner, state consistent "
                    f"(ack won {outcomes['ack_wins']}, escalation won {outcomes['escalation_wins']})")


def s_race_double_escalation(trials=30):
    bad = 0
    for _ in range(trials):
        w = new_world(3)
        with connect() as c:
            iid, _ = report(c, w, severity="CRITICAL")
            auto_assign(c, iid)
        due = now() + dt.timedelta(minutes=6)
        res = in_threads(lambda cn: escalate_one(cn, iid, due), lambda cn: escalate_one(cn, iid, due),
                         lambda cn: escalate_one(cn, iid, due))
        with connect() as c, c.cursor() as cur:
            seq, opened = max_seq(cur, iid), open_assignments(cur, iid)
        if not (sorted(res).count("ESCALATED") == 1 and seq == 2 and opened == 1
                and all(r in ("ESCALATED", "SKIPPED_NOT_DUE") for r in res)):
            bad += 1
            print("   inconsistent:", res, seq, opened)
    check(bad == 0, f"three workers escalate the same incident x{trials}: exactly ONE escalation, no duplicates")


def s_race_assign_vs_assign(trials=20):
    """Two admins assigning at once (manual): second must not create a second open assignment."""
    bad = 0
    for _ in range(trials):
        w = new_world(3)
        r1, r2, r3 = w["responders"]
        with connect() as c:
            iid, _ = report(c, w)

        def manual(target):
            def go(cn):
                with cn.cursor() as cur:
                    inc = one(cur, "LOCK_INCIDENT", (iid,))
                    assign_internal(cur, inc, target, w["admin"], "MANUAL", now())
                cn.commit()
                return "OK"
            return go
        out = in_threads(manual(r2), manual(r3))
        with connect() as c, c.cursor() as cur:
            opened, seq = open_assignments(cur, iid), max_seq(cur, iid)
            inc = incident(cur, iid)
        if not (out == ["OK", "OK"] and opened == 1 and seq == 2 and inc["current_sequence"] == 2):
            bad += 1
            print("   inconsistent:", out, opened, seq)
    check(bad == 0, f"two simultaneous manual assignments x{trials}: serialised, one open assignment, sequences 1..2")


def s_race_same_responder(trials=20):
    """Two incidents auto-assigned at the same instant for the same team must be spread, not stacked."""
    bad = 0
    for _ in range(trials):
        w = new_world(2)
        with connect() as c:
            a, _ = report(c, w)
            b, _ = report(c, w)
        res = in_threads(lambda cn: auto_assign(cn, a), lambda cn: auto_assign(cn, b))
        if len(set(res)) != 2 or any(isinstance(r, str) for r in res):
            bad += 1
            print("   stacked/inconsistent:", res)
    check(bad == 0, f"two incidents competing for one team x{trials}: team lock spreads them across responders")


def s_race_duplicate_events(trials=20):
    """The same INCIDENT_CREATED handled twice concurrently -> one assignment."""
    bad = 0
    for _ in range(trials):
        w = new_world(2)
        with connect() as c:
            iid, _ = report(c, w)
        res = in_threads(lambda cn: auto_assign(cn, iid), lambda cn: auto_assign(cn, iid))
        with connect() as c, c.cursor() as cur:
            seq, opened = max_seq(cur, iid), open_assignments(cur, iid)
            cur.execute("SELECT count(*) AS n FROM workflow_execution WHERE incident_id=%s", (iid,))
            execs = cur.fetchone()["n"]
            cur.execute("SELECT count(*) AS n FROM notification WHERE incident_id=%s", (iid,))
            notifs = cur.fetchone()["n"]
        if not (sorted(map(str, res)).count("NOOP") == 1 and seq == 1 and opened == 1 and execs == 1 and notifs == 1):
            bad += 1
            print("   inconsistent:", res, seq, opened, execs, notifs)
    check(bad == 0, f"duplicate event handled concurrently x{trials}: one assignment, one execution, one notification")


def s_race_report_retry(trials=20):
    bad = 0
    for _ in range(trials):
        w = new_world(1)
        key = str(uuid.uuid4())
        res = in_threads(lambda cn: report(cn, w, key=key), lambda cn: report(cn, w, key=key),
                         lambda cn: report(cn, w, key=key))
        ids = {r[0] for r in res if isinstance(r, tuple)}
        created = [r[1] for r in res if isinstance(r, tuple)].count(True)
        if not (len(ids) == 1 and created == 1):
            bad += 1
            print("   inconsistent:", res)
    check(bad == 0, f"same report request sent 3x concurrently x{trials}: one incident, one creator")


def main():
    for fn in (s_happy_path, s_invalid_transitions, s_escalation_chain, s_skips_unavailable, s_no_responder,
               s_disabled_user_not_chosen, s_resolve_before_escalation, s_scanner_filters, s_sweeper,
               s_notifications, s_race_ack_vs_escalation, s_race_double_escalation, s_race_assign_vs_assign,
               s_race_same_responder, s_race_duplicate_events, s_race_report_retry):
        print(f"-- {fn.__name__}")
        fn()
    print(f"\nRESULT passed={results['pass']} failed={results['fail']}")
    sys.exit(1 if results["fail"] else 0)


main()
