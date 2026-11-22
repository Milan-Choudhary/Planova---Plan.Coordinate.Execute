package com.planova.incident;

import java.time.Instant;
import java.util.UUID;

/** One row of the {@code incident} table, exactly as read by IncidentSql.SELECT_INCIDENT / LOCK_INCIDENT. */
public record IncidentRow(
        UUID id, UUID planId, UUID eventId, String title, String description,
        Severity severity, IncidentStatus status, UUID reporterId, UUID assigneeId,
        Instant ackDeadline, int currentSequence, Instant createdAt, Instant updatedAt,
        Instant acknowledgedAt, Instant resolvedAt, Instant closedAt, String resolutionNote, long version) {
}
