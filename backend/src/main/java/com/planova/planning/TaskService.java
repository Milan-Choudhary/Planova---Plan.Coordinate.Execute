package com.planova.planning;

import com.planova.access.AccessService;
import com.planova.access.AccessService.PlanAccess;
import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.common.Db;
import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import com.planova.notification.NotificationService;
import com.planova.planning.PlanningDtos.CreateTaskRequest;
import com.planova.planning.PlanningDtos.TaskResponse;
import com.planova.planning.PlanningDtos.UpdateTaskRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasks. Editors (admin / plan OWNER / EDITOR) may change anything; the ASSIGNEE of a task may change
 * only its status. Assignees must be eligible workers of the plan (active, not a VIEWER, plan or team member).
 */
@Service
public class TaskService {

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private static final String SELECT = """
            SELECT k.id, k.plan_id, p.title AS plan_title, k.event_id, k.title, k.description, k.assignee_id,
                   u.name AS assignee_name, k.priority, k.status, k.deadline, k.created_by, k.created_at,
                   k.updated_at, k.version
            FROM task k JOIN plan p ON p.id = k.plan_id LEFT JOIN app_user u ON u.id = k.assignee_id
            """;

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public TaskService(JdbcTemplate jdbc, AccessService access, AuditService audit,
                       NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    private TaskResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        TaskStatus status = TaskStatus.valueOf(rs.getString("status"));
        Instant deadline = Db.instant(rs, "deadline");
        return new TaskResponse(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getString("plan_title"), rs.getObject("event_id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getObject("assignee_id", UUID.class), rs.getString("assignee_name"),
                rs.getString("priority"), status, deadline,
                status.isOpen() && deadline != null && deadline.isBefore(clock.instant()),
                rs.getObject("created_by", UUID.class), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    // ================================================================ create / read

    @Transactional
    public TaskResponse create(Actor actor, UUID planId, CreateTaskRequest req) {
        PlanAccess plan = access.requireEdit(planId, actor);
        String priority = req.priority() == null ? "MEDIUM" : req.priority();
        requirePriority(priority);
        requireEventInPlan(planId, req.eventId());
        requireEligibleAssignee(plan, req.assigneeId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id, plan_id, event_id, title, description, assignee_id, priority, deadline, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, planId, req.eventId(), req.title().trim(), req.description(), req.assigneeId(), priority,
                Db.ts(req.deadline()), actor.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", req.title().trim());
        after.put("assigneeId", req.assigneeId());
        after.put("priority", priority);
        after.put("status", "TODO");
        audit.record(actor.id(), "TASK_CREATED", "TASK", id, planId, null, after);
        notifyAssignee(id, req.assigneeId(), actor, req.title().trim(), 0);
        return load(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> listForPlan(Actor actor, UUID planId, TaskStatus status, UUID assigneeId,
                                                  int page, int size) {
        access.requireRead(planId, actor);
        StringBuilder where = new StringBuilder(" WHERE k.plan_id = ?");
        List<Object> args = new ArrayList<>(List.of(planId));
        if (status != null) {
            where.append(" AND k.status = ?");
            args.add(status.name());
        }
        if (assigneeId != null) {
            where.append(" AND k.assignee_id = ?");
            args.add(assigneeId);
        }
        return page(where, args, page, size);
    }

    /** Open tasks assigned to the caller across every plan they can still read. */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> mine(Actor actor, boolean openOnly, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE k.assignee_id = ? AND ")
                .append(AccessService.VISIBLE_PLAN_PREDICATE.replace("p.id", "k.plan_id")
                        .replace("p.team_id", "(SELECT team_id FROM plan WHERE id = k.plan_id)"));
        List<Object> args = new ArrayList<>(List.of(actor.id(), actor.isAdmin(), actor.id(), actor.id()));
        if (openOnly) {
            where.append(" AND k.status IN ('TODO','IN_PROGRESS')");
        }
        return page(where, args, page, size);
    }

    private PageResponse<TaskResponse> page(StringBuilder where, List<Object> args, int page, int size) {
        int p = Db.clampPage(page);
        int s = Db.clampSize(size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM task k" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<TaskResponse> items = jdbc.query(SELECT + where
                        + " ORDER BY (k.status IN ('DONE','CANCELLED')), k.deadline NULLS LAST, k.created_at, k.id"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public TaskResponse get(Actor actor, UUID taskId) {
        TaskResponse t = load(taskId);
        access.requireRead(t.planId(), actor);          // 404 for tasks of plans the caller cannot read
        return t;
    }

    private TaskResponse load(UUID taskId) {
        return jdbc.query(SELECT + " WHERE k.id = ?", (rs, i) -> map(rs), taskId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "Task not found"));
    }

    // ================================================================ update

    @Transactional
    public TaskResponse update(Actor actor, UUID taskId, UpdateTaskRequest req) {
        TaskResponse before = lockTask(taskId);
        PlanAccess plan = access.requireRead(before.planId(), actor);
        if (!actor.canWrite()) {
            throw ApiException.forbidden("FORBIDDEN", "Your role is read-only");
        }
        if (plan.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        boolean editor = AccessService.canEdit(plan, actor);
        boolean isAssignee = actor.id().equals(before.assigneeId());
        boolean onlyStatus = req.title() == null && req.description() == null && req.eventId() == null
                && req.assigneeId() == null && req.clearAssignee() == null && req.priority() == null
                && req.deadline() == null && req.clearDeadline() == null;
        if (!editor && !(isAssignee && onlyStatus)) {
            throw ApiException.forbidden("FORBIDDEN",
                    isAssignee ? "Assignees may only change the status of a task"
                            : "You do not have permission to change this task");
        }
        if (req.expectedVersion() != null && req.expectedVersion() != before.version()) {
            throw ApiException.conflict("STALE_VERSION", "The task was changed by someone else. Reload and retry.");
        }
        if (req.title() != null && req.title().isBlank()) {
            throw ApiException.badRequest("INVALID_TITLE", "title must not be blank");
        }
        if (req.priority() != null) {
            requirePriority(req.priority());
        }
        if (req.status() != null && req.status() != before.status() && !before.status().canTransitionTo(req.status())) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "A task that is " + before.status() + " cannot become " + req.status());
        }
        requireEventInPlan(before.planId(), req.eventId());
        UUID newAssignee = Boolean.TRUE.equals(req.clearAssignee()) ? null
                : req.assigneeId() != null ? req.assigneeId() : before.assigneeId();
        if (req.assigneeId() != null && !req.assigneeId().equals(before.assigneeId())) {
            requireEligibleAssignee(plan, req.assigneeId());
        }
        Instant newDeadline = Boolean.TRUE.equals(req.clearDeadline()) ? null
                : req.deadline() != null ? req.deadline() : before.deadline();
        TaskStatus newStatus = req.status() != null ? req.status() : before.status();

        jdbc.update("""
                UPDATE task SET title = COALESCE(?, title), description = COALESCE(?, description),
                       event_id = COALESCE(?, event_id), assignee_id = ?, priority = COALESCE(?, priority),
                       deadline = ?, status = ?, version = version + 1, updated_at = now()
                WHERE id = ?
                """, req.title() == null ? null : req.title().trim(), req.description(), req.eventId(), newAssignee,
                req.priority(), Db.ts(newDeadline), newStatus.name(), taskId);

        TaskResponse after = load(taskId);
        audit.record(actor.id(), "TASK_UPDATED", "TASK", taskId, before.planId(), summary(before), summary(after));
        if (newAssignee != null && !newAssignee.equals(before.assigneeId())) {
            notifyAssignee(taskId, newAssignee, actor, after.title(), after.version());
        }
        return after;
    }

    private TaskResponse lockTask(UUID taskId) {
        // Serialise two simultaneous edits of one task; state below is read AFTER the lock is held.
        jdbc.queryForList("SELECT id FROM task WHERE id = ? FOR UPDATE", UUID.class, taskId);
        return load(taskId);
    }

    private static Map<String, Object> summary(TaskResponse t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", t.title());
        m.put("status", t.status().name());
        m.put("assigneeId", t.assigneeId());
        m.put("priority", t.priority());
        m.put("deadline", t.deadline() == null ? null : t.deadline().toString());
        m.put("eventId", t.eventId());
        return m;
    }

    private void notifyAssignee(UUID taskId, UUID assigneeId, Actor actor, String title, long version) {
        if (assigneeId == null || assigneeId.equals(actor.id())) {
            return;
        }
        notifications.enqueue(assigneeId, null, "TASK_ASSIGNED", "Task assigned to you", title,
                "TASK_ASSIGNED:" + taskId + ":" + assigneeId + ":" + version);
    }

    private void requirePriority(String priority) {
        if (!PRIORITIES.contains(priority)) {
            throw ApiException.badRequest("INVALID_PRIORITY", "priority must be LOW, MEDIUM, HIGH or CRITICAL");
        }
    }

    private void requireEventInPlan(UUID planId, UUID eventId) {
        if (eventId == null) {
            return;
        }
        Integer n = jdbc.queryForObject("SELECT count(*) FROM plan_event WHERE id = ? AND plan_id = ?",
                Integer.class, eventId, planId);
        if (n == null || n == 0) {
            throw ApiException.badRequest("INVALID_EVENT", "The event does not belong to this plan");
        }
    }

    private void requireEligibleAssignee(PlanAccess plan, UUID assigneeId) {
        if (assigneeId != null && !access.isEligibleWorker(assigneeId, plan)) {
            throw ApiException.badRequest("INELIGIBLE_ASSIGNEE",
                    "That user is not an active, non-viewer member of this plan or its team");
        }
    }
}
