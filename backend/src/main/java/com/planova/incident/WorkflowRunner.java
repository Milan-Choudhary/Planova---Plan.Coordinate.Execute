package com.planova.incident;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the workflow for one incident in its own transaction, with failure bookkeeping.
 *
 * <p>Failure model: if processing throws, that transaction rolls back completely (no half-assigned
 * incident), the failure is counted on the execution row (bounded by max_attempts), and the
 * recovery sweep retries until the limit, after which the execution is FAILED and an administrator
 * must act. Known limit: if there is no ACTIVE workflow definition at all, no execution row can be
 * created, so failures are only logged (see EDGE_CASES.md).
 */
@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final WorkflowService workflow;
    private final IncidentRepository repo;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public WorkflowRunner(WorkflowService workflow, IncidentRepository repo, PlatformTransactionManager txManager,
                          Clock clock) {
        this.workflow = workflow;
        this.repo = repo;
        this.requiresNew = new TransactionTemplate(txManager);
        // After-commit callbacks still have the finished transaction bound to the thread; REQUIRES_NEW
        // guarantees a genuinely new one (see NotificationDispatcher for the same pitfall).
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public WorkflowService.Outcome run(UUID incidentId) {
        try {
            return requiresNew.execute(status -> workflow.autoAssign(incidentId));
        } catch (RuntimeException e) {
            log.error("Workflow failed for incident {}: {}", incidentId, e.toString());
            try {
                String message = String.valueOf(e.getMessage());
                requiresNew.executeWithoutResult(status -> repo.recordExecutionFailure(
                        incidentId, message.length() > 500 ? message.substring(0, 500) : message, clock.instant()));
            } catch (RuntimeException inner) {
                log.error("Could not record workflow failure for {}: {}", incidentId, inner.toString());
            }
            return null;
        }
    }

    /** Crash recovery + bounded retry: picks up incidents committed but not (successfully) processed. */
    public int recover() {
        Instant now = clock.instant();
        List<UUID> ids = repo.needingWorkflow(now.minus(Duration.ofSeconds(5)), now.minus(Duration.ofSeconds(30)), 20);
        for (UUID id : ids) {
            run(id);
        }
        return ids.size();
    }
}
