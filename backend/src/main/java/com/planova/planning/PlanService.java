package com.planova.planning;

import com.planova.access.AccessService;
import com.planova.access.AccessService.PlanAccess;
import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.common.Db;
import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import com.planova.planning.PlanningDtos.CreatePlanRequest;
import com.planova.planning.PlanningDtos.EventRequest;
import com.planova.planning.PlanningDtos.EventResponse;
import com.planova.planning.PlanningDtos.PlanMemberRequest;
import com.planova.planning.PlanningDtos.PlanMemberResponse;
import com.planova.planning.PlanningDtos.PlanResponse;
import com.planova.planning.PlanningDtos.PlanStatusRequest;
import com.planova.planning.PlanningDtos.UpdatePlanRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans, their access list and their events. Every method authorizes through {@link AccessService}. */
@Service
public class PlanService {

    private static final Set<String> VISIBILITY = Set.of("PRIVATE", "TEAM");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "ARCHIVED");

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final AuditService audit;

    public PlanService(JdbcTemplate jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    // ================================================================ plans

    @Transactional
    public PlanResponse create(Actor actor, CreatePlanRequest req) {
        if (!actor.canCreatePlans()) {
            throw ApiException.forbidden("FORBIDDEN", "Only administrators and organizers can create plans");
        }
        validateDates(req.startDate(), req.endDate());
        String visibility = req.visibility() == null ? "PRIVATE" : req.visibility();
        if (!VISIBILITY.contains(visibility)) {
            throw ApiException.badRequest("INVALID_VISIBILITY", "visibility must be PRIVATE or TEAM");
        }
        requireTeamExists(req.teamId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan (id, title, description, owner_id, team_id, start_date, end_date, visibility)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, req.title().trim(), req.description(), actor.id(), req.teamId(),
                req.startDate(), req.endDate(), visibility);
        jdbc.update("INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, 'OWNER')", id, actor.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", req.title().trim());
        after.put("teamId", req.teamId());
        after.put("status", "DRAFT");
        audit.record(actor.id(), "PLAN_CREATED", "PLAN", id, id, null, after);
        return get(id, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> list(Actor actor, String status, String q, int page, int size) {
        int p = Db.clampPage(page);
        int s = Db.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE ").append(AccessService.VISIBLE_PLAN_PREDICATE);
        List<Object> args = new ArrayList<>(List.of(actor.isAdmin(), actor.id(), actor.id()));
        if (status != null && !status.isBlank()) {
            if (!STATUSES.contains(status)) {
                throw ApiException.badRequest("INVALID_STATUS", "Unknown plan status");
            }
            where.append(" AND p.status = ?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND lower(p.title) LIKE ?");
            args.add("%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM plan p" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<UUID> ids = jdbc.queryForList("SELECT p.id FROM plan p" + where
                + " ORDER BY p.updated_at DESC, p.id LIMIT ? OFFSET ?", UUID.class, pageArgs.toArray());
        List<PlanResponse> items = new ArrayList<>();
        for (UUID id : ids) {
            items.add(get(id, actor));
        }
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, p, s, t, (int) ((t + s - 1) / s));
    }

    @Transactional(readOnly = true)
    public PlanResponse get(UUID planId, Actor actor) {
        PlanAccess a = access.requireRead(planId, actor);
        return jdbc.query("""
                SELECT p.id, p.title, p.description, p.owner_id, o.name AS owner_name, p.team_id, t.name AS team_name,
                       p.start_date, p.end_date, p.status, p.visibility, p.created_at, p.updated_at, p.version,
                       (SELECT count(*) FROM plan_event e WHERE e.plan_id = p.id) AS events,
                       (SELECT count(*) FROM task k WHERE k.plan_id = p.id) AS tasks,
                       (SELECT count(*) FROM task k WHERE k.plan_id = p.id AND k.status IN ('TODO','IN_PROGRESS')) AS open_tasks,
                       (SELECT count(*) FROM incident i WHERE i.plan_id = p.id
                            AND i.status NOT IN ('RESOLVED','CLOSED')) AS open_incidents
                FROM plan p JOIN app_user o ON o.id = p.owner_id LEFT JOIN team t ON t.id = p.team_id
                WHERE p.id = ?
                """, (rs, i) -> new PlanResponse(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getObject("team_id", UUID.class), rs.getString("team_name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("status"), rs.getString("visibility"), Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"), rs.getLong("version"), a.planRole(),
                AccessService.canEdit(a, actor) && !a.archived(), AccessService.isOwner(a, actor),
                rs.getLong("events"), rs.getLong("tasks"), rs.getLong("open_tasks"), rs.getLong("open_incidents")),
                planId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "Plan not found"));
    }

    @Transactional
    public PlanResponse update(Actor actor, UUID planId, UpdatePlanRequest req) {
        PlanAccess a = access.requireEdit(planId, actor);
        PlanResponse before = get(planId, actor);
        if (req.expectedVersion() != null && req.expectedVersion() != before.version()) {
            throw ApiException.conflict("STALE_VERSION", "The plan was changed by someone else. Reload and retry.");
        }
        if (req.teamId() != null && !req.teamId().equals(before.teamId()) && !AccessService.isOwner(a, actor)) {
            throw ApiException.forbidden("FORBIDDEN", "Only the plan owner can change the responder team");
        }
        if (req.title() != null && req.title().isBlank()) {
            throw ApiException.badRequest("INVALID_TITLE", "title must not be blank");
        }
        if (req.visibility() != null && !VISIBILITY.contains(req.visibility())) {
            throw ApiException.badRequest("INVALID_VISIBILITY", "visibility must be PRIVATE or TEAM");
        }
        LocalDate start = req.startDate() != null ? req.startDate() : before.startDate();
        LocalDate end = req.endDate() != null ? req.endDate() : before.endDate();
        validateDates(start, end);
        requireTeamExists(req.teamId());
        jdbc.update("""
                UPDATE plan SET title = COALESCE(?, title), description = COALESCE(?, description),
                       team_id = COALESCE(?, team_id), start_date = ?, end_date = ?,
                       visibility = COALESCE(?, visibility), version = version + 1, updated_at = now()
                WHERE id = ?
                """, req.title() == null ? null : req.title().trim(), req.description(), req.teamId(), start, end,
                req.visibility(), planId);
        PlanResponse after = get(planId, actor);
        audit.record(actor.id(), "PLAN_UPDATED", "PLAN", planId, planId, summary(before), summary(after));
        return after;
    }

    /** DRAFT -> ACTIVE -> ARCHIVED (and back to ACTIVE). Archiving is refused while incidents are unresolved. */
    @Transactional
    public PlanResponse changeStatus(Actor actor, UUID planId, PlanStatusRequest req) {
        PlanAccess a = access.requireOwner(planId, actor);
        if (!STATUSES.contains(req.status())) {
            throw ApiException.badRequest("INVALID_STATUS", "status must be DRAFT, ACTIVE or ARCHIVED");
        }
        if (a.status().equals(req.status())) {
            return get(planId, actor);                                           // idempotent
        }
        if ("DRAFT".equals(req.status())) {
            throw ApiException.conflict("INVALID_TRANSITION", "A plan cannot go back to DRAFT");
        }
        if ("ARCHIVED".equals(req.status())) {
            Integer open = jdbc.queryForObject(
                    "SELECT count(*) FROM incident WHERE plan_id = ? AND status NOT IN ('RESOLVED','CLOSED')",
                    Integer.class, planId);
            if (open != null && open > 0) {
                throw ApiException.conflict("OPEN_INCIDENTS",
                        "Resolve the " + open + " open incident(s) before archiving this plan");
            }
        }
        jdbc.update("UPDATE plan SET status = ?, version = version + 1, updated_at = now() WHERE id = ?",
                req.status(), planId);
        audit.record(actor.id(), "PLAN_STATUS_CHANGED", "PLAN", planId, planId,
                Map.of("status", a.status()), Map.of("status", req.status()));
        return get(planId, actor);
    }

    private static Map<String, Object> summary(PlanResponse p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", p.title());
        m.put("description", p.description());
        m.put("teamId", p.teamId());
        m.put("startDate", p.startDate() == null ? null : p.startDate().toString());
        m.put("endDate", p.endDate() == null ? null : p.endDate().toString());
        m.put("visibility", p.visibility());
        return m;
    }

    private void requireTeamExists(UUID teamId) {
        if (teamId == null) {
            return;
        }
        Integer n = jdbc.queryForObject("SELECT count(*) FROM team WHERE id = ?", Integer.class, teamId);
        if (n == null || n == 0) {
            throw ApiException.badRequest("TEAM_NOT_FOUND", "The selected team does not exist");
        }
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("INVALID_DATES", "endDate must not be before startDate");
        }
    }

    // ================================================================ members

    @Transactional(readOnly = true)
    public List<PlanMemberResponse> members(Actor actor, UUID planId) {
        access.requireRead(planId, actor);
        return jdbc.query("""
                SELECT u.id, u.name, u.email, u.role, m.plan_role
                FROM plan_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.plan_id = ?
                ORDER BY CASE m.plan_role WHEN 'OWNER' THEN 0 WHEN 'EDITOR' THEN 1 ELSE 2 END, lower(u.name)
                """, (rs, i) -> new PlanMemberResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("email"), rs.getString("role"), rs.getString("plan_role")), planId);
    }

    @Transactional
    public List<PlanMemberResponse> setMember(Actor actor, UUID planId, PlanMemberRequest req) {
        PlanAccess a = access.requireOwner(planId, actor);
        if (a.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        if (!"EDITOR".equals(req.planRole()) && !"VIEWER".equals(req.planRole())) {
            throw ApiException.badRequest("INVALID_PLAN_ROLE", "planRole must be EDITOR or VIEWER");
        }
        if (req.userId().equals(a.ownerId())) {
            throw ApiException.conflict("OWNER_FIXED", "The plan owner's role cannot be changed");
        }
        List<Map<String, Object>> user = jdbc.queryForList("SELECT role, status FROM app_user WHERE id = ?",
                req.userId());
        if (user.isEmpty() || "DISABLED".equals(user.get(0).get("status"))) {
            throw ApiException.badRequest("USER_NOT_AVAILABLE", "That user does not exist or is disabled");
        }
        if ("EDITOR".equals(req.planRole()) && "VIEWER".equals(user.get(0).get("role"))) {
            throw ApiException.badRequest("VIEWER_NOT_EDITOR", "A read-only VIEWER cannot be a plan editor");
        }
        jdbc.update("""
                INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, ?)
                ON CONFLICT (plan_id, user_id) DO UPDATE SET plan_role = EXCLUDED.plan_role
                """, planId, req.userId(), req.planRole());
        audit.record(actor.id(), "PLAN_MEMBER_SET", "PLAN", planId, planId, null,
                Map.of("userId", req.userId(), "planRole", req.planRole()));
        return members(actor, planId);
    }

    @Transactional
    public List<PlanMemberResponse> removeMember(Actor actor, UUID planId, UUID userId) {
        PlanAccess a = access.requireOwner(planId, actor);
        if (a.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        if (userId.equals(a.ownerId())) {
            throw ApiException.conflict("OWNER_FIXED", "The plan owner cannot be removed");
        }
        int n = jdbc.update("DELETE FROM plan_member WHERE plan_id = ? AND user_id = ?", planId, userId);
        if (n == 0) {
            throw ApiException.notFound("MEMBER_NOT_FOUND", "That user is not a member of this plan");
        }
        audit.record(actor.id(), "PLAN_MEMBER_REMOVED", "PLAN", planId, planId, Map.of("userId", userId), null);
        return members(actor, planId);
    }

    // ================================================================ events

    private static EventResponse mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventResponse(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getString("title"), rs.getString("description"), Db.instant(rs, "start_at"),
                Db.instant(rs, "end_at"), rs.getString("location"), rs.getString("category"), rs.getLong("version"));
    }

    private static final String EVENT_COLUMNS =
            "id, plan_id, title, description, start_at, end_at, location, category, version";

    @Transactional(readOnly = true)
    public List<EventResponse> events(Actor actor, UUID planId) {
        access.requireRead(planId, actor);
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM plan_event WHERE plan_id = ? ORDER BY start_at, id",
                (rs, i) -> mapEvent(rs), planId);
    }

    @Transactional
    public EventResponse createEvent(Actor actor, UUID planId, EventRequest req) {
        access.requireEdit(planId, actor);
        validateEvent(req);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_event (id, plan_id, title, description, start_at, end_at, location, category)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, planId, req.title().trim(), req.description(), Db.ts(req.startAt()), Db.ts(req.endAt()),
                req.location(), req.category());
        audit.record(actor.id(), "EVENT_CREATED", "EVENT", id, planId, null, Map.of("title", req.title().trim()));
        return event(planId, id);
    }

    @Transactional
    public EventResponse updateEvent(Actor actor, UUID planId, UUID eventId, EventRequest req) {
        access.requireEdit(planId, actor);
        validateEvent(req);
        EventResponse before = event(planId, eventId);
        jdbc.update("""
                UPDATE plan_event SET title = ?, description = ?, start_at = ?, end_at = ?, location = ?,
                       category = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND plan_id = ?
                """, req.title().trim(), req.description(), Db.ts(req.startAt()), Db.ts(req.endAt()), req.location(),
                req.category(), eventId, planId);
        audit.record(actor.id(), "EVENT_UPDATED", "EVENT", eventId, planId,
                Map.of("title", before.title()), Map.of("title", req.title().trim()));
        return event(planId, eventId);
    }

    @Transactional
    public void deleteEvent(Actor actor, UUID planId, UUID eventId) {
        access.requireEdit(planId, actor);
        EventResponse before = event(planId, eventId);
        Integer used = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM task WHERE event_id = ?) + (SELECT count(*) FROM incident WHERE event_id = ?)
                """, Integer.class, eventId, eventId);
        if (used != null && used > 0) {
            throw ApiException.conflict("EVENT_IN_USE", "Tasks or incidents still refer to this event");
        }
        jdbc.update("DELETE FROM plan_event WHERE id = ? AND plan_id = ?", eventId, planId);
        audit.record(actor.id(), "EVENT_DELETED", "EVENT", eventId, planId, Map.of("title", before.title()), null);
    }

    private EventResponse event(UUID planId, UUID eventId) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM plan_event WHERE id = ? AND plan_id = ?",
                        (rs, i) -> mapEvent(rs), eventId, planId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "Event not found"));
    }

    private static void validateEvent(EventRequest req) {
        if (!req.endAt().isAfter(req.startAt())) {
            throw ApiException.badRequest("INVALID_TIMES", "endAt must be after startAt");
        }
    }
}
