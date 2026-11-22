package com.planova.incident;

/**
 * Every SQL statement that decides incident correctness lives HERE, as plain text.
 *
 * <p>Why a separate constants class?
 * <ol>
 *   <li>The statements are the heart of the concurrency design (row locks, guarded UPDATEs,
 *       ON CONFLICT dedupe). Keeping them together makes them reviewable in one place.</li>
 *   <li>{@code scripts/verify_incident_sql.py} parses THIS file and executes these exact strings
 *       against a real PostgreSQL with concurrent connections - so the race-condition behaviour
 *       is tested even though the Spring layer could not be compiled in the authoring sandbox.</li>
 * </ol>
 *
 * Conventions: positional {@code ?} parameters; the application passes "now" explicitly (from the
 * injected Clock) instead of using SQL now(), so tests can control time. Each statement is a Java
 * text block assigned to a {@code public static final String} (the verifier relies on that shape).
 */
public final class IncidentSql {

    private IncidentSql() {
    }

    // ------------------------------------------------------------------ reading / locking

    public static final String SELECT_INCIDENT = """
            SELECT id, plan_id, event_id, title, description, severity, status, reporter_id, assignee_id,
                   ack_deadline, current_sequence, created_at, updated_at, acknowledged_at, resolved_at,
                   closed_at, resolution_note, version
            FROM incident WHERE id = ?
            """;

    /** Takes the row lock. Whoever holds it is the only one who may change this incident. */
    public static final String LOCK_INCIDENT = """
            SELECT id, plan_id, event_id, title, description, severity, status, reporter_id, assignee_id,
                   ack_deadline, current_sequence, created_at, updated_at, acknowledged_at, resolved_at,
                   closed_at, resolution_note, version
            FROM incident WHERE id = ? FOR UPDATE
            """;

    // ------------------------------------------------------------------ creation (idempotent)

    /** 0 rows affected = this reporter already sent this Idempotency-Key. */
    public static final String INSERT_INCIDENT = """
            INSERT INTO incident (id, plan_id, event_id, title, description, severity, status,
                                  reporter_id, idempotency_key, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
            ON CONFLICT (reporter_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """;

    public static final String FIND_BY_IDEMPOTENCY = """
            SELECT id FROM incident WHERE reporter_id = ? AND idempotency_key = ?
            """;

    // ------------------------------------------------------------------ assignment

    /**
     * Step 1 of 2. Must run BEFORE the insert: the partial unique index allows only one open
     * assignment per incident and is checked per statement (see DECISIONS.md D-008).
     */
    public static final String SUPERSEDE_OPEN_ASSIGNMENTS = """
            UPDATE incident_assignment SET status = 'SUPERSEDED', ended_at = ?
            WHERE incident_id = ? AND status IN ('ACTIVE', 'ACKNOWLEDGED')
            """;

    /** Step 2 of 2. unique(incident_id, sequence) makes a duplicate escalation fail loudly. */
    public static final String INSERT_ASSIGNMENT = """
            INSERT INTO incident_assignment (id, incident_id, sequence, assignee_id, reason, status,
                                             assigned_by, assigned_at, ack_deadline)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """;

    /** Guarded by current_sequence: if someone else assigned first, 0 rows -> we abort. */
    public static final String SET_ASSIGNED = """
            UPDATE incident SET status = 'ASSIGNED', assignee_id = ?, ack_deadline = ?, current_sequence = ?,
                   acknowledged_at = NULL, resolved_at = NULL, closed_at = NULL,
                   version = version + 1, updated_at = ?
            WHERE id = ? AND current_sequence = ?
            """;

    // ------------------------------------------------------------------ lifecycle transitions
    // Each is guarded by the expected current status, so a stale caller changes nothing (0 rows).

    public static final String ACK_ASSIGNMENT = """
            UPDATE incident_assignment SET status = 'ACKNOWLEDGED', acknowledged_at = ?
            WHERE incident_id = ? AND sequence = ? AND status = 'ACTIVE'
            """;

    public static final String SET_ACKNOWLEDGED = """
            UPDATE incident SET status = 'ACKNOWLEDGED', acknowledged_at = ?, version = version + 1, updated_at = ?
            WHERE id = ? AND status = 'ASSIGNED'
            """;

    public static final String SET_IN_PROGRESS = """
            UPDATE incident SET status = 'IN_PROGRESS', version = version + 1, updated_at = ?
            WHERE id = ? AND status = 'ACKNOWLEDGED'
            """;

    public static final String SET_RESOLVED = """
            UPDATE incident SET status = 'RESOLVED', resolved_at = ?, resolution_note = ?,
                   version = version + 1, updated_at = ?
            WHERE id = ? AND status IN ('ACKNOWLEDGED', 'IN_PROGRESS')
            """;

    public static final String COMPLETE_ASSIGNMENT = """
            UPDATE incident_assignment SET status = 'COMPLETED', ended_at = ?
            WHERE incident_id = ? AND status IN ('ACTIVE', 'ACKNOWLEDGED')
            """;

    public static final String SET_CLOSED = """
            UPDATE incident SET status = 'CLOSED', closed_at = ?, version = version + 1, updated_at = ?
            WHERE id = ? AND status = 'RESOLVED'
            """;

    public static final String SET_REOPENED = """
            UPDATE incident SET status = 'OPEN', assignee_id = NULL, ack_deadline = NULL,
                   resolved_at = NULL, closed_at = NULL, version = version + 1, updated_at = ?
            WHERE id = ? AND status IN ('RESOLVED', 'CLOSED')
            """;

    // ------------------------------------------------------------------ responder selection

    /** Serialises concurrent auto-assignments for one team so each sees the other's load. */
    public static final String LOCK_TEAM = """
            SELECT id FROM team WHERE id = ? FOR UPDATE
            """;

    public static final String TEAM_CANDIDATES = """
            SELECT tm.user_id, tm.escalation_order,
                   (SELECT count(*) FROM incident x
                     WHERE x.assignee_id = tm.user_id
                       AND x.status IN ('ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS')) AS open_load
            FROM team_membership tm
            JOIN app_user u ON u.id = tm.user_id
            WHERE tm.team_id = ? AND tm.escalation_order IS NOT NULL
              AND tm.available AND u.status = 'ACTIVE'
            ORDER BY tm.escalation_order
            """;

    public static final String CHAIN_POSITION = """
            SELECT escalation_order FROM team_membership WHERE team_id = ? AND user_id = ?
            """;

    public static final String PLAN_TEAM = """
            SELECT team_id FROM plan WHERE id = ?
            """;

    // ------------------------------------------------------------------ workflow execution

    public static final String SELECT_ACTIVE_DEFINITION = """
            SELECT id, definition::text AS definition FROM workflow_definition
            WHERE name = 'incident-default' AND status = 'ACTIVE'
            """;

    /**
     * First run inserts. A repeated INCIDENT_CREATED event conflicts and changes NOTHING (the WHERE
     * only lets a finished execution be restarted, e.g. after a reopen).
     */
    public static final String UPSERT_EXECUTION = """
            INSERT INTO workflow_execution (id, incident_id, definition_id, status, started_at, updated_at)
            VALUES (?, ?, ?, 'RUNNING', ?, ?)
            ON CONFLICT (incident_id) DO UPDATE
               SET status = 'RUNNING', attempts = 0, last_error = NULL, finished_at = NULL,
                   definition_id = EXCLUDED.definition_id, updated_at = EXCLUDED.updated_at,
                   version = workflow_execution.version + 1
             WHERE workflow_execution.status IN ('COMPLETED', 'FAILED')
            """;

    public static final String SET_EXECUTION_STATUS = """
            UPDATE workflow_execution SET status = ?, last_error = ?, finished_at = ?, updated_at = ?,
                   version = version + 1
            WHERE incident_id = ?
            """;

    /** Bounded retries: after max_attempts the execution becomes FAILED and stops being retried. */
    public static final String RECORD_EXECUTION_FAILURE = """
            UPDATE workflow_execution
               SET attempts = LEAST(attempts + 1, max_attempts), last_error = ?,
                   status = CASE WHEN attempts + 1 >= max_attempts THEN 'FAILED' ELSE 'RUNNING' END,
                   finished_at = CASE WHEN attempts + 1 >= max_attempts THEN ? ELSE finished_at END,
                   updated_at = ?, version = version + 1
             WHERE incident_id = ?
            """;

    // ------------------------------------------------------------------ scheduler queries

    /** Candidates only; the real decision is re-made under the row lock (stale-job protection). */
    public static final String DUE_FOR_ESCALATION = """
            SELECT i.id FROM incident i
            JOIN workflow_execution w ON w.incident_id = i.id
            WHERE i.status = 'ASSIGNED' AND i.ack_deadline <= ? AND w.status = 'WAITING_ACK'
            ORDER BY i.ack_deadline
            LIMIT ?
            """;

    /** Crash recovery: incidents committed but never processed (process died before the handler ran). */
    public static final String NEEDS_WORKFLOW = """
            SELECT i.id FROM incident i
            LEFT JOIN workflow_execution w ON w.incident_id = i.id
            WHERE i.status = 'OPEN' AND i.created_at <= ?
              AND (w.id IS NULL OR (w.status = 'RUNNING' AND w.attempts < w.max_attempts AND w.updated_at <= ?))
            ORDER BY i.created_at
            LIMIT ?
            """;

    // ------------------------------------------------------------------ notifications

    /** 0 rows affected = this exact notification already exists (duplicate suppressed). */
    public static final String INSERT_NOTIFICATION = """
            INSERT INTO notification (id, recipient_id, incident_id, type, channel, status, title, body,
                                      dedupe_key, created_at)
            VALUES (?, ?, ?, ?, 'IN_APP', 'PENDING', ?, ?, ?, ?)
            ON CONFLICT (recipient_id, channel, dedupe_key) DO NOTHING
            """;

    public static final String MARK_NOTIFICATION_SENT = """
            UPDATE notification SET status = 'SENT', sent_at = ?, attempts = attempts + 1, last_error = NULL
            WHERE id = ? AND status <> 'SENT'
            """;

    public static final String MARK_NOTIFICATION_FAILED = """
            UPDATE notification SET status = 'FAILED', attempts = attempts + 1, last_error = ?
            WHERE id = ? AND status <> 'SENT'
            """;

    /** SKIP LOCKED: several app instances can run the retry job without delivering the same row twice. */
    public static final String NOTIFICATIONS_TO_DELIVER = """
            SELECT id, recipient_id, incident_id, type, title, body, attempts
            FROM notification
            WHERE status <> 'SENT' AND attempts < ?
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
}
