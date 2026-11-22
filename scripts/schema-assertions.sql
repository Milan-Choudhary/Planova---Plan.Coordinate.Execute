-- Schema assertions: prove the database itself rejects impossible states.
-- Run AFTER all migrations. Executed by scripts/verify-migrations.sh.
-- Each expect_fail(...) must raise an error, otherwise the whole script aborts.
-- Everything runs inside one transaction that is rolled back, so no data is left behind.

BEGIN;

CREATE FUNCTION pg_temp.expect_fail(label text, stmt text) RETURNS void AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'PASS  (rejected) %  -> %', label, SQLERRM;
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  (was accepted!) %', label;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.expect_ok(label text, stmt text) RETURNS void AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE 'PASS  (accepted) %', label;
END;
$$ LANGUAGE plpgsql;

-- ---------- fixtures ----------
INSERT INTO app_user (id, email, name, password_hash, role) VALUES
 ('00000000-0000-0000-0000-000000000001', 'org@example.test',  'Org',  'x', 'ORGANIZER'),
 ('00000000-0000-0000-0000-000000000002', 'resp@example.test', 'Resp', 'x', 'TEAM_MEMBER');
INSERT INTO team (id, name) VALUES ('00000000-0000-0000-0000-0000000000a1', 'Ops');
INSERT INTO plan (id, title, owner_id, team_id) VALUES
 ('00000000-0000-0000-0000-0000000000b1', 'Plan A', '00000000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-0000000000a1'),
 ('00000000-0000-0000-0000-0000000000b2', 'Plan B', '00000000-0000-0000-0000-000000000001', NULL);
INSERT INTO plan_event (id, plan_id, title, start_at, end_at) VALUES
 ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', 'Day 1',
  '2026-11-01 09:00+00', '2026-11-01 18:00+00');
INSERT INTO incident (id, plan_id, title, description, severity, reporter_id) VALUES
 ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000b1',
  'Wi-Fi down', 'Auditorium', 'HIGH', '00000000-0000-0000-0000-000000000001');

-- ---------- identity ----------
SELECT pg_temp.expect_fail('duplicate email differing only by case',
 $$INSERT INTO app_user (email,name,password_hash,role) VALUES ('ORG@EXAMPLE.TEST','Dup','x','VIEWER')$$);
SELECT pg_temp.expect_fail('unknown role',
 $$INSERT INTO app_user (email,name,password_hash,role) VALUES ('a@example.test','A','x','SUPERUSER')$$);
SELECT pg_temp.expect_fail('duplicate escalation position in one team',
 $$INSERT INTO team_membership (team_id,user_id,escalation_order) VALUES
   ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001',1),
   ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000002',1)$$);

-- ---------- planning ----------
SELECT pg_temp.expect_fail('plan end_date before start_date',
 $$INSERT INTO plan (title,owner_id,start_date,end_date) VALUES
   ('Bad','00000000-0000-0000-0000-000000000001','2026-12-10','2026-12-01')$$);
SELECT pg_temp.expect_fail('event end not after start',
 $$INSERT INTO plan_event (plan_id,title,start_at,end_at) VALUES
   ('00000000-0000-0000-0000-0000000000b1','Bad','2026-11-01 10:00+00','2026-11-01 10:00+00')$$);
SELECT pg_temp.expect_fail('task pointing at an event of a DIFFERENT plan',
 $$INSERT INTO task (plan_id,event_id,title,created_by) VALUES
   ('00000000-0000-0000-0000-0000000000b2','00000000-0000-0000-0000-0000000000c1','Bad',
    '00000000-0000-0000-0000-000000000001')$$);
SELECT pg_temp.expect_ok('task pointing at event of its own plan',
 $$INSERT INTO task (plan_id,event_id,title,created_by) VALUES
   ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000c1','Ok',
    '00000000-0000-0000-0000-000000000001')$$);
SELECT pg_temp.expect_fail('invalid task status',
 $$INSERT INTO task (plan_id,title,status,created_by) VALUES
   ('00000000-0000-0000-0000-0000000000b1','Bad','FINISHED','00000000-0000-0000-0000-000000000001')$$);

-- ---------- incidents ----------
SELECT pg_temp.expect_fail('ACKNOWLEDGED incident without assignee',
 $$UPDATE incident SET status='ACKNOWLEDGED' WHERE id='00000000-0000-0000-0000-0000000000d1'$$);
SELECT pg_temp.expect_fail('ASSIGNED incident without ack deadline',
 $$UPDATE incident SET status='ASSIGNED', assignee_id='00000000-0000-0000-0000-000000000002'
   WHERE id='00000000-0000-0000-0000-0000000000d1'$$);
SELECT pg_temp.expect_fail('invalid severity',
 $$INSERT INTO incident (plan_id,title,description,severity,reporter_id) VALUES
   ('00000000-0000-0000-0000-0000000000b1','t','d','URGENT','00000000-0000-0000-0000-000000000001')$$);
SELECT pg_temp.expect_ok('first create with idempotency key',
 $$INSERT INTO incident (plan_id,title,description,severity,reporter_id,idempotency_key) VALUES
   ('00000000-0000-0000-0000-0000000000b1','t','d','LOW','00000000-0000-0000-0000-000000000001','key-1')$$);
SELECT pg_temp.expect_fail('retry of the same create request (same reporter + key)',
 $$INSERT INTO incident (plan_id,title,description,severity,reporter_id,idempotency_key) VALUES
   ('00000000-0000-0000-0000-0000000000b1','t','d','LOW','00000000-0000-0000-0000-000000000001','key-1')$$);

SELECT pg_temp.expect_ok('assignment #1',
 $$INSERT INTO incident_assignment (incident_id,sequence,assignee_id,reason) VALUES
   ('00000000-0000-0000-0000-0000000000d1',1,'00000000-0000-0000-0000-000000000002','AUTO')$$);
SELECT pg_temp.expect_fail('duplicate escalation: second worker also creates assignment #1',
 $$INSERT INTO incident_assignment (incident_id,sequence,assignee_id,reason) VALUES
   ('00000000-0000-0000-0000-0000000000d1',1,'00000000-0000-0000-0000-000000000001','ESCALATION')$$);
SELECT pg_temp.expect_fail('two open assignments at once (#2 while #1 still ACTIVE)',
 $$INSERT INTO incident_assignment (incident_id,sequence,assignee_id,reason) VALUES
   ('00000000-0000-0000-0000-0000000000d1',2,'00000000-0000-0000-0000-000000000001','ESCALATION')$$);
-- DESIGN RULE found by this test: "supersede old + insert new" must be TWO statements in one
-- transaction (UPDATE first, then INSERT). A single data-modifying CTE is rejected because
-- the unique index is checked per statement. The escalation service must follow this order.
SELECT pg_temp.expect_fail('single-statement CTE supersede+insert (index checked per statement)',
 $$WITH s AS (UPDATE incident_assignment SET status='SUPERSEDED', ended_at=now()
              WHERE incident_id='00000000-0000-0000-0000-0000000000d1' AND sequence=1)
   INSERT INTO incident_assignment (incident_id,sequence,assignee_id,reason) VALUES
   ('00000000-0000-0000-0000-0000000000d1',2,'00000000-0000-0000-0000-000000000001','ESCALATION')$$);
SELECT pg_temp.expect_ok('supersede #1 (statement 1 of 2)',
 $$UPDATE incident_assignment SET status='SUPERSEDED', ended_at=now()
   WHERE incident_id='00000000-0000-0000-0000-0000000000d1' AND sequence=1$$);
SELECT pg_temp.expect_ok('create #2 (statement 2 of 2)',
 $$INSERT INTO incident_assignment (incident_id,sequence,assignee_id,reason) VALUES
   ('00000000-0000-0000-0000-0000000000d1',2,'00000000-0000-0000-0000-000000000001','ESCALATION')$$);

-- ---------- automation ----------
SELECT pg_temp.expect_ok('workflow definition v1 ACTIVE',
 $$INSERT INTO workflow_definition (id,name,version,status,definition) VALUES
   ('00000000-0000-0000-0000-0000000000e1','assertion-flow',1,'ACTIVE','{}')$$);
SELECT pg_temp.expect_fail('second ACTIVE version of the same workflow',
 $$INSERT INTO workflow_definition (name,version,status,definition) VALUES
   ('assertion-flow',2,'ACTIVE','{}')$$);
SELECT pg_temp.expect_ok('workflow execution for incident',
 $$INSERT INTO workflow_execution (incident_id,definition_id) VALUES
   ('00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000e1')$$);
SELECT pg_temp.expect_fail('duplicate INCIDENT_CREATED -> second execution for same incident',
 $$INSERT INTO workflow_execution (incident_id,definition_id) VALUES
   ('00000000-0000-0000-0000-0000000000d1','00000000-0000-0000-0000-0000000000e1')$$);

SELECT pg_temp.expect_ok('notification created',
 $$INSERT INTO notification (recipient_id,incident_id,type,title,dedupe_key) VALUES
   ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-0000000000d1',
    'INCIDENT_ASSIGNED','Assigned','INCIDENT_ASSIGNED:d1:1')$$);
SELECT pg_temp.expect_fail('duplicate notification (same recipient/channel/dedupe key)',
 $$INSERT INTO notification (recipient_id,incident_id,type,title,dedupe_key) VALUES
   ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-0000000000d1',
    'INCIDENT_ASSIGNED','Assigned','INCIDENT_ASSIGNED:d1:1')$$);

-- ---------- audit ----------
SELECT pg_temp.expect_ok('audit row appended',
 $$INSERT INTO audit_record (actor_id,action,entity_type,entity_id) VALUES
   ('00000000-0000-0000-0000-000000000001','INCIDENT_CREATED','INCIDENT',
    '00000000-0000-0000-0000-0000000000d1')$$);
SELECT pg_temp.expect_fail('audit row UPDATE',
 $$UPDATE audit_record SET action='TAMPERED'$$);
SELECT pg_temp.expect_fail('audit row DELETE', $$DELETE FROM audit_record$$);
SELECT pg_temp.expect_fail('audit TRUNCATE',   $$TRUNCATE audit_record$$);

ROLLBACK;
\warn ALL SCHEMA ASSERTIONS PASSED
