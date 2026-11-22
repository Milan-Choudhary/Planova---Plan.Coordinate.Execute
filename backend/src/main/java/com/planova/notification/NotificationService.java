package com.planova.notification;

import com.planova.common.Db;
import com.planova.incident.IncidentSql;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates notifications. The row is written INSIDE the caller's business transaction (so it exists
 * if and only if the assignment/escalation committed), but actual DELIVERY happens after commit in
 * {@link NotificationDispatcher}. Consequence: a delivery failure can never roll back a valid
 * assignment (brief section 8), and a crash after commit leaves a PENDING row the retry job picks up.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NotificationDispatcher dispatcher;

    public NotificationService(JdbcTemplate jdbc, Clock clock, NotificationDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dispatcher = dispatcher;
    }

    /**
     * @param dedupeKey deterministic, e.g. "INCIDENT_ASSIGNED:&lt;incidentId&gt;:&lt;sequence&gt;". Calling this
     *                  twice with the same key (retry, duplicate event) inserts nothing the second time.
     * @return true if a new notification was created, false if it was a duplicate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(UUID recipientId, UUID incidentId, String type, String title, String body,
                           String dedupeKey) {
        int inserted = jdbc.update(IncidentSql.INSERT_NOTIFICATION, UUID.randomUUID(), recipientId, incidentId,
                type, title, body, dedupeKey, Db.ts(clock.instant()));
        if (inserted == 0) {
            log.debug("Duplicate notification suppressed: {}", dedupeKey);
            return false;
        }
        // Deliver only once the surrounding transaction has really committed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.deliverPending();
                } catch (RuntimeException e) {
                    // Never let delivery problems surface into the business operation; the retry job continues.
                    log.warn("Notification delivery pass failed: {}", e.getMessage());
                }
            }
        });
        return true;
    }

    /** All active administrators: the human fallback when automation cannot proceed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueForAdmins(UUID incidentId, String type, String title, String body, String dedupeKey) {
        List<UUID> admins = jdbc.queryForList(
                "SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'", UUID.class);
        for (UUID admin : admins) {
            enqueue(admin, incidentId, type, title, body, dedupeKey);
        }
    }
}
