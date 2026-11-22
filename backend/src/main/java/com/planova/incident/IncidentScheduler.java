package com.planova.incident;

import com.planova.notification.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-driven jobs. They only FIND work; every decision is re-made under the incident row lock
 * (see EscalationService), so a late or duplicated tick is harmless.
 *
 * <p>Disabled in tests ({@code planova.scheduler.enabled=false}); tests call the services directly
 * with a controlled Clock instead of sleeping.
 */
@Component
@ConditionalOnProperty(name = "planova.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class IncidentScheduler {

    private static final Logger log = LoggerFactory.getLogger(IncidentScheduler.class);

    private final EscalationService escalation;
    private final WorkflowRunner runner;
    private final NotificationDispatcher dispatcher;

    public IncidentScheduler(EscalationService escalation, WorkflowRunner runner, NotificationDispatcher dispatcher) {
        this.escalation = escalation;
        this.runner = runner;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${planova.scheduler.escalation-interval-ms:15000}",
            initialDelayString = "${planova.scheduler.escalation-interval-ms:15000}")
    public void escalateOverdue() {
        try {
            int n = escalation.escalateDue();
            if (n > 0) {
                log.info("Escalated {} overdue incident(s)", n);
            }
        } catch (RuntimeException e) {
            log.error("Escalation sweep failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${planova.scheduler.recovery-interval-ms:30000}",
            initialDelayString = "${planova.scheduler.recovery-interval-ms:30000}")
    public void recoverUnprocessed() {
        try {
            int n = runner.recover();
            if (n > 0) {
                log.info("Recovery sweep processed {} incident(s)", n);
            }
        } catch (RuntimeException e) {
            log.error("Recovery sweep failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${planova.scheduler.notification-interval-ms:20000}",
            initialDelayString = "${planova.scheduler.notification-interval-ms:20000}")
    public void retryNotifications() {
        try {
            dispatcher.deliverPending();
        } catch (RuntimeException e) {
            log.error("Notification retry failed: {}", e.toString());
        }
    }
}
