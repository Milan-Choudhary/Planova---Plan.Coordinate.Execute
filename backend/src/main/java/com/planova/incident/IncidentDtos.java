package com.planova.incident;

import com.planova.audit.AuditQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** HTTP shapes of the incident API (mirrored in docs/API_CONTRACTS.md). */
public final class IncidentDtos {

    private IncidentDtos() {
    }

    public record ReportIncidentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description,
            /** Optional: the reporter's own estimate. The server may only RAISE it (SeverityPolicy). */
            Severity severity,
            /** Optional: the event of the same plan this incident relates to. */
            UUID eventId) {
    }

    public record AssignRequest(@NotNull UUID assigneeId, @Size(max = 500) String note) {
    }

    /**
     * Body of acknowledge / reject / start / resolve / close / reopen. Everything is optional.
     * {@code expectedVersion}: if given and the incident has changed since the client loaded it, 409.
     */
    public record ActionRequest(Long expectedVersion, @Size(max = 2000) String note) {
    }

    public record IncidentResponse(
            UUID id, UUID planId, String planTitle, UUID eventId, String title, String description,
            Severity severity, IncidentStatus status,
            UUID reporterId, String reporterName, UUID assigneeId, String assigneeName,
            Instant ackDeadline, boolean ackOverdue, int escalationLevel,
            Instant createdAt, Instant updatedAt, Instant acknowledgedAt, Instant resolvedAt, Instant closedAt,
            String resolutionNote, long version,
            /** Buttons this caller may press right now. Advisory; the server re-checks every action. */
            List<String> allowedActions) {
    }

    public record AssignmentResponse(
            int sequence, UUID assigneeId, String assigneeName, String reason, String status,
            UUID assignedBy, String assignedByName, Instant assignedAt, Instant ackDeadline,
            Instant acknowledgedAt, Instant endedAt) {
    }

    public record IncidentHistoryResponse(List<AssignmentResponse> assignments,
                                          List<AuditQueryService.Entry> audit) {
    }

    /** Result of a report call: {@code created=false} means an idempotent replay of an earlier request. */
    public record ReportResult(IncidentResponse incident, boolean created) {
    }
}
