package com.planova.access;

import com.planova.common.ApiException;
import com.planova.identity.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * THE place that answers "may this caller touch this plan?". Every plan-scoped endpoint calls it,
 * which is what stops a user from reading or changing a plan by guessing its ID (invariant 9).
 *
 * <p>Rules (full table in REQUIREMENTS.md):
 * <ul>
 *   <li><b>read</b>: ADMIN, or a plan member (any plan role), or a member of the plan's team
 *       (so responders can see the incidents they must handle).</li>
 *   <li><b>edit</b>: ADMIN, or plan role OWNER/EDITOR - and never a global VIEWER.</li>
 *   <li>A plan the caller cannot read answers <b>404</b>, not 403, so its existence is not leaked.</li>
 *   <li>An ARCHIVED plan is read-only for everybody (409 PLAN_ARCHIVED on writes).</li>
 * </ul>
 */
@Service
public class AccessService {

    public record PlanAccess(UUID planId, String status, UUID teamId, UUID ownerId, String planRole,
                             boolean teamMember) {

        public boolean archived() {
            return "ARCHIVED".equals(status);
        }
    }

    /** SQL fragment: plans visible to a caller. Parameters, in order: isAdmin, userId, userId. */
    public static final String VISIBLE_PLAN_PREDICATE = """
            (? OR p.id IN (SELECT plan_id FROM plan_member WHERE user_id = ?)
                OR p.team_id IN (SELECT team_id FROM team_membership WHERE user_id = ?))
            """;

    private final JdbcTemplate jdbc;

    public AccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private PlanAccess load(UUID planId, Actor actor) {
        List<PlanAccess> rows = jdbc.query("""
                SELECT p.id, p.status, p.team_id, p.owner_id,
                       COALESCE(pm.plan_role, CASE WHEN ? AND EXISTS (
                           SELECT 1 FROM team_membership lead WHERE lead.team_id = p.team_id
                           AND lead.user_id = ? AND lead.team_role = 'LEAD'
                       ) THEN 'EDITOR' END) AS plan_role,
                       EXISTS (SELECT 1 FROM team_membership tm
                                WHERE tm.team_id = p.team_id AND tm.user_id = ?) AS team_member
                FROM plan p
                LEFT JOIN plan_member pm ON pm.plan_id = p.id AND pm.user_id = ?
                WHERE p.id = ?
                """,
                (rs, i) -> new PlanAccess(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getObject("team_id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getString("plan_role"), rs.getBoolean("team_member")),
                actor.canCreatePlans(), actor.id(), actor.id(), actor.id(), planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static boolean canRead(PlanAccess a, Actor actor) {
        return actor.isAdmin() || a.planRole() != null || a.teamMember();
    }

    public static boolean canEdit(PlanAccess a, Actor actor) {
        return actor.canWrite()
                && (actor.isAdmin() || "OWNER".equals(a.planRole()) || "EDITOR".equals(a.planRole()));
    }

    public static boolean isOwner(PlanAccess a, Actor actor) {
        return actor.canWrite() && (actor.isAdmin() || "OWNER".equals(a.planRole()));
    }

    /** 404 unless the caller may read the plan. */
    public PlanAccess requireRead(UUID planId, Actor actor) {
        PlanAccess a = load(planId, actor);
        if (a == null || !canRead(a, actor)) {
            throw ApiException.notFound("PLAN_NOT_FOUND", "Plan not found");
        }
        return a;
    }

    /** 404 if unreadable, 403 if readable but not editable, 409 if archived. */
    public PlanAccess requireEdit(UUID planId, Actor actor) {
        PlanAccess a = requireRead(planId, actor);
        if (!canEdit(a, actor)) {
            throw ApiException.forbidden("FORBIDDEN", "You do not have permission to change this plan");
        }
        if (a.archived()) {
            throw ApiException.conflict("PLAN_ARCHIVED", "This plan is archived and read-only");
        }
        return a;
    }

    public PlanAccess requireOwner(UUID planId, Actor actor) {
        PlanAccess a = requireRead(planId, actor);
        if (!isOwner(a, actor)) {
            throw ApiException.forbidden("FORBIDDEN", "Only the plan owner can do this");
        }
        return a;
    }

    /**
     * May this user be given work (task / incident) on this plan? Must be an ACTIVE, non-VIEWER user
     * who is a plan member or a member of the plan's team.
     */
    public boolean isEligibleWorker(UUID userId, PlanAccess plan) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM app_user u
                WHERE u.id = ? AND u.status = 'ACTIVE' AND u.role <> 'VIEWER'
                  AND (EXISTS (SELECT 1 FROM plan_member pm WHERE pm.plan_id = ? AND pm.user_id = u.id)
                    OR EXISTS (SELECT 1 FROM team_membership tm WHERE tm.team_id = ? AND tm.user_id = u.id))
                """, Integer.class, userId, plan.planId(), plan.teamId());
        return n != null && n > 0;
    }
}
