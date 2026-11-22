package com.planova.incident;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The incident lifecycle (documented in STATE_MACHINES.md). Pure Java: no Spring, no database,
 * so the rules can be unit-tested in isolation and reused by every layer.
 *
 * <pre>
 *   OPEN ──assign──▶ ASSIGNED ──ack──▶ ACKNOWLEDGED ──start──▶ IN_PROGRESS
 *                       │                    │                      │
 *                       │                    └────resolve───────────┤
 *                       ▼                                           ▼
 *            (escalate/reassign: stays ASSIGNED,                RESOLVED ──close──▶ CLOSED
 *             new assignment sequence)                              │                 │
 *                                       OPEN ◀──────reopen──────────┴─────────────────┘
 * </pre>
 *
 * Reassignment / escalation is NOT a status change: it replaces the current assignment
 * (new sequence number) while the incident stays ASSIGNED. See DECISIONS.md D-013.
 */
public enum IncidentStatus {
    OPEN, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, CLOSED;

    private static final Map<IncidentStatus, Set<IncidentStatus>> ALLOWED = Map.of(
            OPEN, EnumSet.of(ASSIGNED),
            ASSIGNED, EnumSet.of(ACKNOWLEDGED),
            ACKNOWLEDGED, EnumSet.of(IN_PROGRESS, RESOLVED),
            IN_PROGRESS, EnumSet.of(RESOLVED),
            RESOLVED, EnumSet.of(CLOSED, OPEN),
            CLOSED, EnumSet.of(OPEN));

    public boolean canTransitionTo(IncidentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** True while somebody is expected to be working on it (counts toward a responder's load). */
    public boolean isActiveWork() {
        return this == ASSIGNED || this == ACKNOWLEDGED || this == IN_PROGRESS;
    }

    /** States from which a manual (re)assignment is allowed. */
    public boolean canBeAssigned() {
        return this == OPEN || isActiveWork();
    }

    /** "Open" for dashboards and for the archive-plan guard. */
    public boolean isUnresolved() {
        return this != RESOLVED && this != CLOSED;
    }
}
