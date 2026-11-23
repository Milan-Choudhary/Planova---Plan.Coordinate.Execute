package com.planova.incident;

import com.planova.access.AccessService;
import com.planova.access.AccessService.PlanAccess;
import com.planova.audit.AuditQueryService;
import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.common.Db;
import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import com.planova.incident.IncidentDtos.AssignRequest;
import com.planova.incident.IncidentDtos.AssignmentResponse;
import com.planova.incident.IncidentDtos.ActionRequest;
import com.planova.incident.IncidentDtos.IncidentHistoryResponse;
import com.planova.incident.IncidentDtos.IncidentResponse;
import com.planova.incident.IncidentDtos.ReportIncidentRequest;
import com.planova.incident.IncidentDtos.ReportResult;
import com.planova.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every incident use-case that a human triggers. The shape of every action method is the same:
 *
 * <ol>
 *   <li>peek (no lock) -> plan access check, so unreadable incidents answer 404 before anything else;</li>
 *   <li>take the incident ROW LOCK and re-read the state - all decisions use that fresh state;</li>
 *   <li>who-may-do-this checks (403, or 409 NOT_CURRENT_ASSIGNEE for a stale ex-assignee);</li>
 *   <li>idempotent repeat of the SAME action returns the current state without side effects;</li>
 *   <li>optional {@code expectedVersion} stale check (409 STALE_VERSION);</li>
 *   <li>guarded UPDATEs (0 rows = somebody else won), audit row, optional notification.</li>
 * </ol>
 * The scheduler's escalation takes the same lock, so "acknowledge" and "escalate" can never both win.
 */
@Service
public class IncidentService {

    private final IncidentRepository repo;
    private final AccessService access;
    private final AssignmentService assignments;
    private final WorkflowService workflow;
    private final EscalationService escalation;
    private final AuditService audit;
    private final AuditQueryService auditQuery;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IncidentService(IncidentRepository repo, AccessService access, AssignmentService assignments,
                           WorkflowService workflow, EscalationService escalation, AuditService audit,
                           AuditQueryService auditQuery, NotificationService notifications,
                           ApplicationEventPublisher events, JdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.assignments = assignments;
        this.workflow = workflow;
        this.escalation = escalation;
        this.audit = audit;
        this.auditQuery = auditQuery;
        this.notifications = notifications;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Row (as read under the lock) together with the caller's plan access. */
    private record Ctx(IncidentRow row, PlanAccess plan) {
        boolean canManage(Actor actor) {
            return AccessService.canEdit(plan, actor);
        }

        boolean isAssignee(Actor actor) {
            return actor.id().equals(row.assigneeId());
        }

        boolean isReporter(Actor actor) {
            return actor.id().equals(row.reporterId());
        }
    }

    // ================================================================ report

    @Transactional
    public ReportResult report(UUID planId, ReportIncidentRequest req, String idempotencyKey, Actor actor) {
        PlanAccess plan = access.requireRead(planId, actor);
        if (!actor.canWrite()) {
            throw ApiException.forbidden("FORBIDDEN", "Your role is read-only and cannot report incidents");
        }
        if (plan.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        String key = normalizeKey(idempotencyKey);
        if (req.eventId() != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM plan_event WHERE id = ? AND plan_id = ?",
                    Integer.class, req.eventId(), planId);
            if (n == null || n == 0) {
                throw ApiException.badRequest("INVALID_EVENT", "The event does not belong to this plan");
            }
        }
        // Fast path for a retry: no insert attempt, no extra audit row.
        if (key != null) {
            var existing = repo.findByIdempotency(actor.id(), key);
            if (existing.isPresent()) {
                return new ReportResult(view(existing.get(), actor), false);
            }
        }

        SeverityPolicy.Result sev = SeverityPolicy.evaluate(req.title(), req.description(), req.severity());
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        boolean inserted = repo.insert(id, planId, req.eventId(), req.title().trim(), req.description().trim(),
                sev.severity(), actor.id(), key, now);
        if (!inserted) {
            // Lost a race with an identical concurrent request: the unique index made us a no-op.
            UUID existing = repo.findByIdempotency(actor.id(), key)
                    .orElseThrow(() -> ApiException.conflict("STALE_VERSION", "Please retry"));
            return new ReportResult(view(existing, actor), false);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", IncidentStatus.OPEN.name());
        after.put("severity", sev.severity().name());
        after.put("severityReason", sev.reason());
        after.put("title", req.title().trim());
        audit.record(actor.id(), "INCIDENT_CREATED", "INCIDENT", id, planId, null, after);

        // Handled only AFTER this transaction commits (IncidentEventListener).
        events.publishEvent(new IncidentCreatedEvent(id));
        return new ReportResult(view(id, actor), true);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String k = key.trim();
        if (k.length() > 100) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be at most 100 characters");
        }
        return k;
    }

    // ================================================================ read

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID id, Actor actor) {
        return view(id, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> list(Actor actor, UUID planId, Set<IncidentStatus> statuses,
                                               boolean assignedToMe, boolean unresolvedOnly, int page, int size) {
        int p = Db.clampPage(page);
        int s = Db.clampSize(size);
        StringBuilder from = new StringBuilder("""
                FROM incident i JOIN plan p ON p.id = i.plan_id
                LEFT JOIN plan_member pm ON pm.plan_id = p.id AND pm.user_id = ?
                WHERE
                """).append(AccessService.VISIBLE_PLAN_PREDICATE);
        List<Object> args = new ArrayList<>(List.of(actor.id(), actor.isAdmin(), actor.id(), actor.id()));
        if (planId != null) {
            from.append(" AND i.plan_id = ?");
            args.add(planId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            from.append(" AND i.status IN (")
                    .append(statuses.stream().map(x -> "?").collect(Collectors.joining(",")))
                    .append(")");
            statuses.forEach(x -> args.add(x.name()));
        }
        if (unresolvedOnly) {
            from.append(" AND i.status NOT IN ('RESOLVED','CLOSED')");
        }
        if (assignedToMe) {
            from.append(" AND i.assignee_id = ?");
            args.add(actor.id());
        }
        Long total = jdbc.queryForObject("SELECT count(*) " + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        // Newest activity first; id as tie-break keeps paging stable.
        List<UUID> ids = jdbc.queryForList(
                "SELECT i.id " + from + " ORDER BY i.updated_at DESC, i.id LIMIT ? OFFSET ?",
                UUID.class, pageArgs.toArray());
        List<IncidentResponse> items = new ArrayList<>();
        for (UUID id : ids) {
            items.add(view(id, actor));
        }
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public IncidentHistoryResponse history(UUID id, Actor actor) {
        IncidentRow row = repo.find(id).orElseThrow(IncidentService::notFound);
        access.requireRead(row.planId(), actor);
        List<AssignmentResponse> rows = jdbc.query("""
                SELECT a.sequence, a.assignee_id, au.name AS assignee_name, a.reason, a.status,
                       a.assigned_by, bu.name AS by_name, a.assigned_at, a.ack_deadline, a.acknowledged_at, a.ended_at
                FROM incident_assignment a
                JOIN app_user au ON au.id = a.assignee_id
                LEFT JOIN app_user bu ON bu.id = a.assigned_by
                WHERE a.incident_id = ? ORDER BY a.sequence
                """, (rs, i) -> new AssignmentResponse(rs.getInt("sequence"), rs.getObject("assignee_id", UUID.class),
                rs.getString("assignee_name"), rs.getString("reason"), rs.getString("status"),
                rs.getObject("assigned_by", UUID.class), rs.getString("by_name"), Db.instant(rs, "assigned_at"),
                Db.instant(rs, "ack_deadline"), Db.instant(rs, "acknowledged_at"), Db.instant(rs, "ended_at")), id);
        return new IncidentHistoryResponse(rows, auditQuery.forEntity("INCIDENT", id));
    }

    // ================================================================ actions

    @Transactional
    public IncidentResponse acknowledge(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        requireAssignee(c, actor);
        if (c.row.status() == IncidentStatus.ACKNOWLEDGED) {
            return view(id, actor);                                   // idempotent repeat
        }
        requireStatus(c, IncidentStatus.ASSIGNED, "acknowledged");
        requireVersion(c, req);
        Instant now = clock.instant();
        if (!repo.ackAssignment(id, c.row.currentSequence(), now) || !repo.setAcknowledged(id, now)) {
            throw stale();
        }
        audit.record(actor.id(), "INCIDENT_ACKNOWLEDGED", "INCIDENT", id, c.row.planId(),
                snapshot(c.row), Map.of("status", "ACKNOWLEDGED", "sequence", c.row.currentSequence()));
        tellReporter(c, actor, "INCIDENT_ACKNOWLEDGED", "Your incident was acknowledged",
                c.row.title() + " is being handled.", "INCIDENT_ACKNOWLEDGED:" + id + ":" + c.row.currentSequence());
        return view(id, actor);
    }

    @Transactional
    public IncidentResponse start(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        requireAssignee(c, actor);
        if (c.row.status() == IncidentStatus.IN_PROGRESS) {
            return view(id, actor);
        }
        requireStatus(c, IncidentStatus.ACKNOWLEDGED, "started");
        requireVersion(c, req);
        if (!repo.setInProgress(id, clock.instant())) {
            throw stale();
        }
        audit.record(actor.id(), "INCIDENT_STARTED", "INCIDENT", id, c.row.planId(),
                snapshot(c.row), Map.of("status", "IN_PROGRESS"));
        return view(id, actor);
    }

    @Transactional
    public IncidentResponse resolve(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        if (!c.isAssignee(actor) && !c.canManage(actor)) {
            throw notAssignee(id, actor);
        }
        if (c.row.status() == IncidentStatus.RESOLVED) {
            return view(id, actor);
        }
        if (c.row.status() != IncidentStatus.ACKNOWLEDGED && c.row.status() != IncidentStatus.IN_PROGRESS) {
            throw invalid(c, "resolved");
        }
        requireVersion(c, req);
        Instant now = clock.instant();
        String note = req == null || req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (!repo.setResolved(id, note, now)) {
            throw stale();
        }
        repo.completeAssignment(id, now);
        // Resolved: the execution is done, so the escalation scanner can never pick this incident up again.
        repo.setExecutionStatus(id, "COMPLETED", null, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "RESOLVED");
        after.put("resolutionNote", note);
        audit.record(actor.id(), "INCIDENT_RESOLVED", "INCIDENT", id, c.row.planId(), snapshot(c.row), after);
        tellReporter(c, actor, "INCIDENT_RESOLVED", "Your incident was resolved",
                c.row.title() + " has been marked resolved.", "INCIDENT_RESOLVED:" + id + ":" + c.row.currentSequence());
        return view(id, actor);
    }

    @Transactional
    public IncidentResponse close(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        requireManagerOrReporter(c, actor);
        if (c.row.status() == IncidentStatus.CLOSED) {
            return view(id, actor);
        }
        requireStatus(c, IncidentStatus.RESOLVED, "closed");
        requireVersion(c, req);
        if (!repo.setClosed(id, clock.instant())) {
            throw stale();
        }
        audit.record(actor.id(), "INCIDENT_CLOSED", "INCIDENT", id, c.row.planId(),
                snapshot(c.row), Map.of("status", "CLOSED"));
        return view(id, actor);
    }

    @Transactional
    public IncidentResponse reopen(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        requireManagerOrReporter(c, actor);
        if (c.row.status() != IncidentStatus.RESOLVED && c.row.status() != IncidentStatus.CLOSED) {
            throw invalid(c, "reopened");
        }
        requireVersion(c, req);
        if (!repo.setReopened(id, clock.instant())) {
            throw stale();
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "OPEN");
        after.put("note", req == null ? null : req.note());
        audit.record(actor.id(), "INCIDENT_REOPENED", "INCIDENT", id, c.row.planId(), snapshot(c.row), after);
        // Same transaction: either it is reopened AND handed to a responder, or (no responder) it stays
        // OPEN with the administrators notified by WorkflowService.
        workflow.autoAssign(id);
        return view(id, actor);
    }

    @Transactional
    public IncidentResponse assign(UUID id, AssignRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        if (!c.canManage(actor)) {
            throw ApiException.forbidden("FORBIDDEN", "Only an administrator or plan editor can assign incidents");
        }
        if (!c.row.status().canBeAssigned()) {
            throw invalid(c, "assigned");
        }
        if (!access.isEligibleWorker(req.assigneeId(), c.plan)) {
            throw ApiException.badRequest("INELIGIBLE_ASSIGNEE",
                    "That user is not an active, non-viewer member of this plan or its team");
        }
        if (req.assigneeId().equals(c.row.assigneeId()) && c.row.status() == IncidentStatus.ASSIGNED) {
            throw ApiException.conflict("ALREADY_ASSIGNED", "The incident is already assigned to that user");
        }
        assignments.assign(c.row, req.assigneeId(), actor.id(), "MANUAL");
        return view(id, actor);
    }

    /** The assignee declines: hand it to the next responder in the chain right away. */
    @Transactional
    public IncidentResponse reject(UUID id, ActionRequest req, Actor actor) {
        Ctx c = begin(id, actor);
        requireAssignee(c, actor);
        requireStatus(c, IncidentStatus.ASSIGNED, "rejected");
        requireVersion(c, req);
        EscalationService.Outcome outcome = escalation.escalateOne(id, true, actor.id());
        switch (outcome) {
            case ESCALATED -> audit.record(actor.id(), "INCIDENT_REJECTED", "INCIDENT", id, c.row.planId(),
                    snapshot(c.row), Map.of("note", req == null || req.note() == null ? "" : req.note()));
            case EXHAUSTED -> throw ApiException.conflict("NO_NEXT_RESPONDER",
                    "There is nobody further down the escalation chain; ask an administrator to assign it");
            case NOT_CURRENT_ASSIGNEE -> throw notAssignee(id, actor);
            default -> throw invalid(c, "rejected");
        }
        return view(id, actor);
    }

    // ================================================================ helpers

    private Ctx begin(UUID id, Actor actor) {
        IncidentRow peek = repo.find(id).orElseThrow(IncidentService::notFound);
        PlanAccess plan = access.requireRead(peek.planId(), actor);            // 404 if unreadable
        if (!actor.canWrite()) {
            throw ApiException.forbidden("FORBIDDEN", "Your role is read-only");
        }
        if (plan.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        IncidentRow row = repo.lock(id).orElseThrow(IncidentService::notFound); // fresh state under the lock
        return new Ctx(row, plan);
    }

    private void requireAssignee(Ctx c, Actor actor) {
        if (!c.isAssignee(actor)) {
            throw notAssignee(c.row.id(), actor);
        }
    }

    /**
     * Somebody who WAS assigned but no longer is (escalated past them) gets a 409 explaining that,
     * because "you lost the race" is not a permissions problem; everybody else gets 403.
     */
    private ApiException notAssignee(UUID incidentId, Actor actor) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM incident_assignment WHERE incident_id = ? AND assignee_id = ?",
                Integer.class, incidentId, actor.id());
        if (n != null && n > 0) {
            return ApiException.conflict("NOT_CURRENT_ASSIGNEE",
                    "This incident is no longer assigned to you (it was escalated or reassigned)");
        }
        return ApiException.forbidden("FORBIDDEN", "You are not allowed to do this to this incident");
    }

    private void requireManagerOrReporter(Ctx c, Actor actor) {
        if (!c.canManage(actor) && !c.isReporter(actor)) {
            throw ApiException.forbidden("FORBIDDEN", "Only the reporter or a plan editor can do this");
        }
    }

    private void requireStatus(Ctx c, IncidentStatus expected, String verb) {
        if (c.row.status() != expected) {
            throw invalid(c, verb);
        }
    }

    private ApiException invalid(Ctx c, String verb) {
        return ApiException.conflict("INVALID_TRANSITION",
                "An incident that is " + c.row.status() + " cannot be " + verb);
    }

    private void requireVersion(Ctx c, ActionRequest req) {
        if (req != null && req.expectedVersion() != null && req.expectedVersion() != c.row.version()) {
            throw stale();
        }
    }

    private static ApiException stale() {
        return ApiException.conflict("STALE_VERSION", "The incident was changed by someone else. Reload and retry.");
    }

    private static ApiException notFound() {
        return ApiException.notFound("INCIDENT_NOT_FOUND", "Incident not found");
    }

    private void tellReporter(Ctx c, Actor actor, String type, String title, String body, String dedupeKey) {
        if (!actor.id().equals(c.row.reporterId())) {
            notifications.enqueue(c.row.reporterId(), c.row.id(), type, title, body, dedupeKey);
        }
    }

    private static Map<String, Object> snapshot(IncidentRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("assigneeId", r.assigneeId());
        m.put("sequence", r.currentSequence());
        return m;
    }

    // ---- response building -------------------------------------------------------------------

    private IncidentResponse view(UUID id, Actor actor) {
        IncidentRow row = repo.find(id).orElseThrow(IncidentService::notFound);
        PlanAccess plan = access.requireRead(row.planId(), actor);            // 404 unless readable
        Map<UUID, String> names = names(row.reporterId(), row.assigneeId());
        String planTitle = jdbc.queryForObject("SELECT title FROM plan WHERE id = ?", String.class, row.planId());
        boolean canManage = AccessService.canEdit(plan, actor) && !plan.archived();
        List<String> actions = !actor.canWrite() || plan.archived() ? List.of()
                : IncidentPolicy.allowedActions(row.status(), actor.id().equals(row.assigneeId()), canManage,
                actor.id().equals(row.reporterId()));
        boolean overdue = row.status() == IncidentStatus.ASSIGNED && row.ackDeadline() != null
                && row.ackDeadline().isBefore(clock.instant());
        return new IncidentResponse(row.id(), row.planId(), planTitle, row.eventId(), row.title(), row.description(),
                row.severity(), row.status(), row.reporterId(), names.get(row.reporterId()), row.assigneeId(),
                row.assigneeId() == null ? null : names.get(row.assigneeId()), row.ackDeadline(), overdue,
                Math.max(row.currentSequence() - 1, 0), row.createdAt(), row.updatedAt(), row.acknowledgedAt(),
                row.resolvedAt(), row.closedAt(), row.resolutionNote(), row.version(), actions);
    }

    private Map<UUID, String> names(UUID... ids) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : ids) {
            if (id != null && !out.containsKey(id)) {
                List<String> found = jdbc.queryForList("SELECT name FROM app_user WHERE id = ?", String.class, id);
                if (!found.isEmpty()) {
                    out.put(id, found.get(0));
                }
            }
        }
        return out;
    }
}
