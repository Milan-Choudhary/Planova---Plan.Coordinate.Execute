package com.planova.planning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** HTTP shapes of teams, plans, events and tasks (contract mirrored in docs/API_CONTRACTS.md). */
public final class PlanningDtos {

    private PlanningDtos() {
    }

    // ---------------------------------------------------------------- teams
    public record CreateTeamRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {
    }

    public record TeamMemberRequest(@NotNull UUID userId,
                                    @NotBlank String teamRole,          // LEAD | RESPONDER | MEMBER
                                    Boolean available,
                                    Integer escalationOrder) {          // null = not part of the chain
    }

    public record AvailabilityRequest(@NotNull Boolean available) {
    }

    public record TeamMemberResponse(UUID userId, String name, String email, String userRole, String teamRole,
                                     boolean available, Integer escalationOrder) {
    }

    public record TeamResponse(UUID id, String name, String description, int memberCount) {
    }

    public record TeamDetailResponse(UUID id, String name, String description,
                                     java.util.List<TeamMemberResponse> members) {
    }

    // ---------------------------------------------------------------- plans
    public record CreatePlanRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                                    UUID teamId, LocalDate startDate, LocalDate endDate, String visibility) {
    }

    /** Partial update: only non-null fields change. */
    public record UpdatePlanRequest(@Size(max = 200) String title, @Size(max = 5000) String description,
                                    UUID teamId, LocalDate startDate, LocalDate endDate, String visibility,
                                    Long expectedVersion) {
    }

    public record PlanStatusRequest(@NotBlank String status) {   // DRAFT | ACTIVE | ARCHIVED
    }

    public record PlanResponse(UUID id, String title, String description, UUID ownerId, String ownerName,
                               UUID teamId, String teamName, LocalDate startDate, LocalDate endDate, String status,
                               String visibility, Instant createdAt, Instant updatedAt, long version,
                               String myPlanRole, boolean canEdit, boolean canManage,
                               long eventCount, long taskCount, long openTaskCount, long openIncidentCount) {
    }

    public record PlanMemberRequest(@NotNull UUID userId, @NotBlank String planRole) {   // EDITOR | VIEWER
    }

    public record PlanMemberResponse(UUID userId, String name, String email, String userRole, String planRole) {
    }

    // ---------------------------------------------------------------- events
    public record EventRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                               @NotNull Instant startAt, @NotNull Instant endAt, @Size(max = 200) String location,
                               @Size(max = 60) String category) {
    }

    public record EventResponse(UUID id, UUID planId, String title, String description, Instant startAt,
                                Instant endAt, String location, String category, long version) {
    }

    // ---------------------------------------------------------------- tasks
    public record CreateTaskRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description,
                                    UUID eventId, UUID assigneeId, String priority, Instant deadline) {
    }

    /** Partial update. A non-editor assignee may send only {@code status}. */
    public record UpdateTaskRequest(@Size(max = 200) String title, @Size(max = 5000) String description,
                                    UUID eventId, UUID assigneeId, Boolean clearAssignee, String priority,
                                    Instant deadline, Boolean clearDeadline, TaskStatus status,
                                    Long expectedVersion) {
    }

    public record TaskResponse(UUID id, UUID planId, String planTitle, UUID eventId, String title, String description,
                               UUID assigneeId, String assigneeName, String priority, TaskStatus status,
                               Instant deadline, boolean overdue, UUID createdBy, Instant createdAt, Instant updatedAt,
                               long version) {
    }
}
