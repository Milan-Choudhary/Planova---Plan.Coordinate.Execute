package com.planova.incident;

import com.planova.audit.AuditService;
import com.planova.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fixed MVP workflow (STATE_MACHINES.md):
 * {@code INCIDENT_CREATED -> CHECK_SEVERITY -> SELECT_RESPONDER -> ASSIGN -> NOTIFY -> WAIT_FOR_ACK}.
 *
 * <ul>
 *   <li>CHECK_SEVERITY ran when the incident was reported ({@link SeverityPolicy}).</li>
 *   <li>SELECT_RESPONDER / ASSIGN / NOTIFY run here, in ONE transaction.</li>
 *   <li>WAIT_FOR_ACK + ESCALATE are driven by {@link EscalationService} and the scheduler.</li>
 * </ul>
 * Idempotent: running it twice for one incident (duplicate event, retry, crash recovery) assigns once.
 */
@Service
public class WorkflowService {

    public enum Outcome { ASSIGNED, NO_RESPONDER, NOOP }

    private final IncidentRepository repo;
    private final WorkflowConfigProvider workflowConfig;
    private final AssignmentService assignments;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public WorkflowService(IncidentRepository repo, WorkflowConfigProvider workflowConfig,
                           AssignmentService assignments, AuditService audit, NotificationService notifications,
                           Clock clock) {
        this.repo = repo;
        this.workflowConfig = workflowConfig;
        this.assignments = assignments;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Caller supplies the transaction (request thread for reopen, TransactionTemplate for events). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome autoAssign(UUID incidentId) {
        IncidentRow incident = repo.lock(incidentId).orElse(null);       // row lock: duplicates queue up here
        if (incident == null || incident.status() != IncidentStatus.OPEN) {
            return Outcome.NOOP;                                          // already handled by someone else
        }
        Instant now = clock.instant();
        repo.upsertExecution(incidentId, workflowConfig.active().definitionId(), now);

        Optional<UUID> team = repo.planTeam(incident.planId());
        List<ResponderSelector.Candidate> candidates = List.of();
        if (team.isPresent()) {
            repo.lockTeam(team.get());           // concurrent assignments for one team see each other's load
            candidates = repo.candidates(team.get());
        }

        Optional<ResponderSelector.Candidate> pick = ResponderSelector.pickInitial(candidates);
        if (pick.isEmpty()) {
            repo.setExecutionStatus(incidentId, "FAILED", "NO_ELIGIBLE_RESPONDER", now);
            audit.record(null, "NO_ELIGIBLE_RESPONDER", "INCIDENT", incidentId, incident.planId(), null,
                    Map.of("status", incident.status().name(), "teamId", String.valueOf(team.orElse(null))));
            notifications.enqueueForAdmins(incidentId, "NO_ELIGIBLE_RESPONDER", "No responder available",
                    "Nobody is available to take: " + incident.title() + ". Please assign it manually.",
                    "NO_RESPONDER:" + incidentId + ":" + incident.currentSequence());
            return Outcome.NO_RESPONDER;
        }
        assignments.assign(incident, pick.get().userId(), null, "AUTO");
        return Outcome.ASSIGNED;
    }
}
