package com.planova.notification;

import com.planova.common.Db;
import com.planova.incident.IncidentSql;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers PENDING / FAILED notifications through their channel, recording the outcome.
 *
 * <ul>
 *   <li>Bounded: a notification gets at most {@code planova.notifications.max-attempts} tries
 *       (default 3), then stays FAILED and visible - never retried forever.</li>
 *   <li>Safe to run on several instances: rows are claimed with FOR UPDATE SKIP LOCKED.</li>
 *   <li>One bad notification never blocks the others: failures are caught per row.</li>
 * </ul>
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NotificationChannel channel;
    private final Clock clock;
    private final int maxAttempts;

    public NotificationDispatcher(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                  NotificationChannel channel, Clock clock,
                                  @Value("${planova.notifications.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc;
        // REQUIRES_NEW is essential: this runs from an afterCommit callback, where the original
        // (already committed) transaction is still bound to the thread. Joining it would silently
        // discard our writes; a new transaction really commits them.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.channel = channel;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    /** @return number of notifications delivered successfully in this pass */
    public int deliverPending() {
        Integer delivered = tx.execute(status -> {
            List<NotificationChannel.Message> batch = jdbc.query(IncidentSql.NOTIFICATIONS_TO_DELIVER,
                    (rs, i) -> new NotificationChannel.Message(rs.getObject("id", UUID.class),
                            rs.getObject("recipient_id", UUID.class), rs.getObject("incident_id", UUID.class),
                            rs.getString("type"), rs.getString("title"), rs.getString("body")),
                    maxAttempts, 50);
            int ok = 0;
            for (NotificationChannel.Message m : batch) {
                try {
                    channel.deliver(m);
                    jdbc.update(IncidentSql.MARK_NOTIFICATION_SENT, Db.ts(clock.instant()), m.id());
                    ok++;
                } catch (Exception e) {
                    log.warn("Notification {} delivery failed: {}", m.id(), e.getMessage());
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    jdbc.update(IncidentSql.MARK_NOTIFICATION_FAILED,
                            reason.length() > 500 ? reason.substring(0, 500) : reason, m.id());
                }
            }
            return ok;
        });
        return delivered == null ? 0 : delivered;
    }
}
