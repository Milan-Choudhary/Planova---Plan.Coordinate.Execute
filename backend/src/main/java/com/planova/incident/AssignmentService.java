package com.planova.incident;

import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONE implementation of "give this incident to somebody". Used by automatic assignment,
 * manual assignment, escalation and reopen, so they all obey the same invariants.
 *
 * <p>{@code Propagation.MANDATORY}: the caller must already hold a transaction AND the incident's row
 * lock (IncidentRepository.lock). That lock is what serialises competing assignments; this class
 * only runs the statements in the order the database constraints require (verified by
 * scripts/verify_incident_sql.py).
 */
@Service
public class AssignmentService {

    private final IncidentRepository repo;
    private final WorkflowConfigProvider workflowConfig;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public AssignmentService(IncidentRepository repo, WorkflowConfigProvider workflowConfig, AuditService audit,
                             NotificationService notifications, Clock clock) {
        this.repo = repo;
        this.workflowConfig = workflowConfig;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * @param incident   the row as read UNDER the lock
     * @param assignedBy the human who did it, or null for the system (automation / escalation)
     * @param reason     AUTO, MANUAL or ESCALATION (stored on the assignment row)
     * @return the new assignment sequence number
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int assign(IncidentRow incident, UUID assigneeId, UUID assignedBy, String reason) {
        Instant now = clock.instant();
        WorkflowConfigProvider.ActiveWorkflow workflow = workflowConfig.active();
        int sequence = incident.currentSequence() + 1;
        Instant deadline = now.plus(workflow.config().ackTimeout(incident.severity()));

        // 1) retire the current assignment BEFORE inserting the next (partial unique index, D-008)
        repo.supersedeOpenAssignments(incident.id(), now);
        // 2) unique(incident_id, sequence): a second worker doing the same escalation fails here
        repo.insertAssignment(incident.id(), sequence, assigneeId, reason, assignedBy, now, deadline);
        // 3) guarded by the sequence we read under the lock; 0 rows means somebody got in first
        if (!repo.setAssigned(incident.id(), assigneeId, deadline, sequence, incident.currentSequence(), now)) {
            throw ApiException.conflict("STALE_VERSION", "The incident was changed by someone else. Reload and retry.");
        }
        // make sure an execution exists (manual assignment of an incident the automation never touched)
        repo.upsertExecution(incident.id(), workflow.definitionId(), now);
        repo.setExecutionStatus(incident.id(), "WAITING_ACK", null, now);

        notifications.enqueue(assigneeId, incident.id(), "INCIDENT_ASSIGNED",
                switch (reason) {
                    case "ESCALATION" -> "Incident escalated to you";
                    case "MANUAL" -> "Incident assigned to you";
                    default -> "New incident assigned to you";
                },
                incident.title() + " (" + incident.severity() + "). Please acknowledge before "
                        + deadline + ".",
                "INCIDENT_ASSIGNED:" + incident.id() + ":" + sequence);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", incident.status().name());
        before.put("assigneeId", incident.assigneeId());
        before.put("sequence", incident.currentSequence());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", IncidentStatus.ASSIGNED.name());
        after.put("assigneeId", assigneeId);
        after.put("sequence", sequence);
        after.put("reason", reason);
        after.put("ackDeadline", deadline.toString());
        audit.record(assignedBy, "ESCALATION".equals(reason) ? "INCIDENT_ESCALATED" : "INCIDENT_ASSIGNED",
                "INCIDENT", incident.id(), incident.planId(), before, after);
        return sequence;
    }
}
