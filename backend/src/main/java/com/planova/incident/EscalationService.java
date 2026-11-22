package com.planova.incident;

import com.planova.audit.AuditService;
import com.planova.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deadline-based escalation.
 *
 * <p>The scanner ({@link #escalateDue()}) only finds CANDIDATES. The real decision is made in
 * {@link #escalateOne} AFTER taking the incident's row lock and re-reading the CURRENT state, so a stale
 * scheduled job can never overwrite a newer acknowledgement or resolution (invariant 4):
 * <ul>
 *   <li>acknowledged / resolved / closed in the meantime -> SKIPPED_STATE, nothing changes</li>
 *   <li>deadline moved (someone reassigned) -> SKIPPED_NOT_DUE</li>
 *   <li>three workers racing -> the row lock queues them; the first escalates, the others see the new deadline</li>
 * </ul>
 */
@Service
public class EscalationService {

    public enum Outcome { ESCALATED, SKIPPED_STATE, SKIPPED_NOT_DUE, EXHAUSTED, NOT_CURRENT_ASSIGNEE }

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);
    private static final int BATCH = 50;

    private final IncidentRepository repo;
    private final WorkflowConfigProvider workflowConfig;
    private final AssignmentService assignments;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public EscalationService(IncidentRepository repo, WorkflowConfigProvider workflowConfig,
                             AssignmentService assignments, AuditService audit, NotificationService notifications,
                             Clock clock, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.workflowConfig = workflowConfig;
        this.assignments = assignments;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Scheduler entry point. One transaction per incident: a failure on one never blocks the rest. */
    public int escalateDue() {
        int escalated = 0;
        List<UUID> due = repo.dueForEscalation(clock.instant(), BATCH);
        for (UUID id : due) {
            try {
                Outcome outcome = requiresNew.execute(status -> escalateOne(id, false, null));
                if (outcome == Outcome.ESCALATED) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                log.error("Escalation of incident {} failed (will be retried): {}", id, e.toString());
            }
        }
        return escalated;
    }

    /**
     * @param force            true for "responder rejected": ignore the deadline, but never mark the
     *                         execution as exhausted - the caller reports the problem instead
     * @param expectedAssignee when not null, only escalate if this user is STILL the assignee
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome escalateOne(UUID incidentId, boolean force, UUID expectedAssignee) {
        IncidentRow incident = repo.lock(incidentId).orElse(null);        // fresh state, under the lock
        if (incident == null || incident.status() != IncidentStatus.ASSIGNED) {
            return Outcome.SKIPPED_STATE;
        }
        if (expectedAssignee != null && !expectedAssignee.equals(incident.assigneeId())) {
            return Outcome.NOT_CURRENT_ASSIGNEE;
        }
        Instant now = clock.instant();
        if (!force && incident.ackDeadline().isAfter(now)) {
            return Outcome.SKIPPED_NOT_DUE;
        }

        WorkflowConfig config = workflowConfig.active().config();
        Optional<UUID> team = repo.planTeam(incident.planId());
        boolean underLimit = incident.currentSequence() - 1 < config.maxEscalationLevel();

        Optional<ResponderSelector.Candidate> next = Optional.empty();
        if (team.isPresent() && (underLimit || force)) {
            int after = repo.chainPosition(team.get(), incident.assigneeId());
            next = ResponderSelector.pickNext(repo.candidates(team.get()), after, incident.assigneeId());
        }
        if (next.isEmpty()) {
            if (!force) {
                markExhausted(incident, now);
            }
            return Outcome.EXHAUSTED;
        }
        assignments.assign(incident, next.get().userId(), null, "ESCALATION");
        return Outcome.ESCALATED;
    }

    /** End of the chain: stop automation (execution FAILED, never rescanned) and tell the humans. */
    private void markExhausted(IncidentRow incident, Instant now) {
        repo.setExecutionStatus(incident.id(), "FAILED", "ESCALATION_EXHAUSTED", now);
        audit.record(null, "ESCALATION_EXHAUSTED", "INCIDENT", incident.id(), incident.planId(), null,
                Map.of("status", incident.status().name(), "sequence", incident.currentSequence()));
        notifications.enqueueForAdmins(incident.id(), "ESCALATION_EXHAUSTED", "Incident needs a human",
                "Nobody acknowledged: " + incident.title() + ". The escalation chain is exhausted.",
                "ESCALATION_EXHAUSTED:" + incident.id() + ":" + incident.currentSequence());
    }
}
