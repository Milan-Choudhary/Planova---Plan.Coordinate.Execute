package com.planova.notification;

import java.util.UUID;

/**
 * One way of delivering a notification. Only IN_APP exists; EMAIL is deliberately NOT faked
 * (deferred - see PROGRESS.md). Adding email later = one more implementation of this interface;
 * the retry/dedupe machinery already exists.
 */
public interface NotificationChannel {

    record Message(UUID id, UUID recipientId, UUID incidentId, String type, String title, String body) {
    }

    /** Must throw to signal failure; the dispatcher records it and retries within a bounded limit. */
    void deliver(Message message) throws Exception;
}
