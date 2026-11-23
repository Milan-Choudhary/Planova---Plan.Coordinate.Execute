package com.planova.planning;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Task lifecycle (documented in STATE_MACHINES.md). Pure Java.
 * <pre>
 *   TODO ⇄ IN_PROGRESS ──▶ DONE ──▶ IN_PROGRESS (reopen)
 *     │         │
 *     └─────────┴──▶ CANCELLED ──▶ TODO (restore)
 * </pre>
 */
public enum TaskStatus {
    TODO, IN_PROGRESS, DONE, CANCELLED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            TODO, EnumSet.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, EnumSet.of(TODO, DONE, CANCELLED),
            DONE, EnumSet.of(IN_PROGRESS),
            CANCELLED, EnumSet.of(TODO));

    public boolean canTransitionTo(TaskStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Open tasks can become overdue; finished or cancelled ones cannot. */
    public boolean isOpen() {
        return this == TODO || this == IN_PROGRESS;
    }
}
