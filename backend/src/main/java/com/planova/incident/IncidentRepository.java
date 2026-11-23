package com.planova.incident;

import com.planova.common.Db;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Thin typed wrapper around {@link IncidentSql}. No logic lives here: it only binds parameters and
 * maps rows, so the interesting behaviour stays in the (separately verified) SQL and the services.
 */
@Repository
public class IncidentRepository {

    private static final RowMapper<IncidentRow> MAPPER = (rs, i) -> new IncidentRow(
            Db.uuid(rs, "id"), Db.uuid(rs, "plan_id"), Db.uuid(rs, "event_id"),
            rs.getString("title"), rs.getString("description"),
            Severity.valueOf(rs.getString("severity")), IncidentStatus.valueOf(rs.getString("status")),
            Db.uuid(rs, "reporter_id"), Db.uuid(rs, "assignee_id"), Db.instant(rs, "ack_deadline"),
            rs.getInt("current_sequence"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"),
            Db.instant(rs, "acknowledged_at"), Db.instant(rs, "resolved_at"), Db.instant(rs, "closed_at"),
            rs.getString("resolution_note"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public IncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IncidentRow> find(UUID id) {
        return jdbc.query(IncidentSql.SELECT_INCIDENT, MAPPER, id).stream().findFirst();
    }

    /** Row lock: only valid inside a transaction; held until it ends. */
    public Optional<IncidentRow> lock(UUID id) {
        return jdbc.query(IncidentSql.LOCK_INCIDENT, MAPPER, id).stream().findFirst();
    }

    /** @return false if this reporter already used this idempotency key */
    public boolean insert(UUID id, UUID planId, UUID eventId, String title, String description, Severity severity,
                          UUID reporterId, String idempotencyKey, Instant now) {
        return jdbc.update(IncidentSql.INSERT_INCIDENT, id, planId, eventId, title, description, severity.name(),
                reporterId, idempotencyKey, Db.ts(now), Db.ts(now)) == 1;
    }

    public Optional<UUID> findByIdempotency(UUID reporterId, String key) {
        return jdbc.queryForList(IncidentSql.FIND_BY_IDEMPOTENCY, UUID.class, reporterId, key).stream().findFirst();
    }

    // ---- assignment (order matters: supersede, then insert, then guarded update) -------------

    public void supersedeOpenAssignments(UUID incidentId, Instant now) {
        jdbc.update(IncidentSql.SUPERSEDE_OPEN_ASSIGNMENTS, Db.ts(now), incidentId);
    }

    public void insertAssignment(UUID incidentId, int sequence, UUID assigneeId, String reason, UUID assignedBy,
                                 Instant now, Instant ackDeadline) {
        jdbc.update(IncidentSql.INSERT_ASSIGNMENT, UUID.randomUUID(), incidentId, sequence, assigneeId, reason,
                assignedBy, Db.ts(now), Db.ts(ackDeadline));
    }

    public boolean setAssigned(UUID incidentId, UUID assigneeId, Instant ackDeadline, int newSequence,
                               int expectedPreviousSequence, Instant now) {
        return jdbc.update(IncidentSql.SET_ASSIGNED, assigneeId, Db.ts(ackDeadline), newSequence, Db.ts(now),
                incidentId, expectedPreviousSequence) == 1;
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    public boolean ackAssignment(UUID incidentId, int sequence, Instant now) {
        return jdbc.update(IncidentSql.ACK_ASSIGNMENT, Db.ts(now), incidentId, sequence) == 1;
    }

    public boolean setAcknowledged(UUID id, Instant now) {
        return jdbc.update(IncidentSql.SET_ACKNOWLEDGED, Db.ts(now), Db.ts(now), id) == 1;
    }

    public boolean setInProgress(UUID id, Instant now) {
        return jdbc.update(IncidentSql.SET_IN_PROGRESS, Db.ts(now), id) == 1;
    }

    public boolean setResolved(UUID id, String note, Instant now) {
        return jdbc.update(IncidentSql.SET_RESOLVED, Db.ts(now), note, Db.ts(now), id) == 1;
    }

    public void completeAssignment(UUID id, Instant now) {
        jdbc.update(IncidentSql.COMPLETE_ASSIGNMENT, Db.ts(now), id);
    }

    public boolean setClosed(UUID id, Instant now) {
        return jdbc.update(IncidentSql.SET_CLOSED, Db.ts(now), Db.ts(now), id) == 1;
    }

    public boolean setReopened(UUID id, Instant now) {
        return jdbc.update(IncidentSql.SET_REOPENED, Db.ts(now), id) == 1;
    }

    // ---- team / chain ------------------------------------------------------------------------

    public Optional<UUID> planTeam(UUID planId) {
        return jdbc.query(IncidentSql.PLAN_TEAM, (rs, i) -> Db.uuid(rs, "team_id"), planId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void lockTeam(UUID teamId) {
        jdbc.queryForList(IncidentSql.LOCK_TEAM, UUID.class, teamId);   // result unused: the lock is the point
    }

    public List<ResponderSelector.Candidate> candidates(UUID teamId) {
        return jdbc.query(IncidentSql.TEAM_CANDIDATES,
                (rs, i) -> new ResponderSelector.Candidate(Db.uuid(rs, "user_id"), rs.getInt("escalation_order"),
                        rs.getInt("open_load")), teamId);
    }

    /** Chain position of a user in a team, or 0 if they are not in the chain. */
    public int chainPosition(UUID teamId, UUID userId) {
        return jdbc.query(IncidentSql.CHAIN_POSITION, (rs, i) -> rs.getInt("escalation_order"), teamId, userId)
                .stream().findFirst().orElse(0);
    }

    // ---- workflow execution ------------------------------------------------------------------

    public record Definition(UUID id, String json) {
    }

    public Definition activeDefinition() {
        return jdbc.query(IncidentSql.SELECT_ACTIVE_DEFINITION,
                        (rs, i) -> new Definition(Db.uuid(rs, "id"), rs.getString("definition")))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No ACTIVE incident-default workflow definition"));
    }

    public void upsertExecution(UUID incidentId, UUID definitionId, Instant now) {
        jdbc.update(IncidentSql.UPSERT_EXECUTION, UUID.randomUUID(), incidentId, definitionId, Db.ts(now), Db.ts(now));
    }

    public void setExecutionStatus(UUID incidentId, String status, String error, Instant now) {
        boolean finished = "COMPLETED".equals(status) || "FAILED".equals(status);
        jdbc.update(IncidentSql.SET_EXECUTION_STATUS, status, error, finished ? Db.ts(now) : null, Db.ts(now),
                incidentId);
    }

    public void recordExecutionFailure(UUID incidentId, String error, Instant now) {
        jdbc.update(IncidentSql.RECORD_EXECUTION_FAILURE, error, Db.ts(now), Db.ts(now), incidentId);
    }

    // ---- scheduler ---------------------------------------------------------------------------

    public List<UUID> dueForEscalation(Instant now, int limit) {
        return jdbc.queryForList(IncidentSql.DUE_FOR_ESCALATION, UUID.class, Db.ts(now), limit);
    }

    public List<UUID> needingWorkflow(Instant createdBefore, Instant updatedBefore, int limit) {
        return jdbc.queryForList(IncidentSql.NEEDS_WORKFLOW, UUID.class, Db.ts(createdBefore), Db.ts(updatedBefore),
                limit);
    }
}
