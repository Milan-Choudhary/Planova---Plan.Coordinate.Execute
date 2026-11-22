package com.planova.incident;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: Spring holds the event back until the reporting
 * transaction has COMMITTED. If it rolled back, the event is dropped - so the workflow can never start
 * for an incident that does not exist. If the process dies between commit and this handler, the
 * recovery sweep in {@link WorkflowRunner#recover()} picks the incident up (no outbox needed for the MVP).
 */
@Component
public class IncidentEventListener {

    private final WorkflowRunner runner;

    public IncidentEventListener(WorkflowRunner runner) {
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentCreated(IncidentCreatedEvent event) {
        runner.run(event.incidentId());
    }
}
