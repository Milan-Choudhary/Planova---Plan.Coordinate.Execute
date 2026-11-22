package com.planova.notification;

import org.springframework.stereotype.Component;

/**
 * In-app delivery: the notification row IS the inbox entry, so "delivering" has nothing further to
 * do. It exists so the pipeline (PENDING -> SENT / FAILED, attempts, retries) is the same one email
 * will use, and so tests can replace it with a failing channel to prove failure handling.
 */
@Component
public class InAppChannel implements NotificationChannel {

    @Override
    public void deliver(Message message) {
        // nothing to send: the inbox query reads the notification table directly
    }
}
