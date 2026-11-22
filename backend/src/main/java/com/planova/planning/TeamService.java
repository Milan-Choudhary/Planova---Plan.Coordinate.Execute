package com.planova.planning;

import com.planova.audit.AuditService;
import com.planova.common.ApiException;
import com.planova.identity.Actor;
import com.planova.planning.PlanningDtos.AvailabilityRequest;
import com.planova.planning.PlanningDtos.CreateTeamRequest;
import com.planova.planning.PlanningDtos.TeamDetailResponse;
import com.planova.planning.PlanningDtos.TeamMemberRequest;
import com.planova.planning.PlanningDtos.TeamMemberResponse;
import com.planova.planning.PlanningDtos.TeamResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams and the escalation chain. Only administrators change teams; any member may flip their OWN
 * availability (so a responder can go off-shift without bothering an admin).
 */
@Service
public class TeamService {

    private static final Set<String> TEAM_ROLES = Set.of("LEAD", "RESPONDER", "MEMBER");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TeamService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Admins and organizers see every team (organizers need to pick one for a plan); others see their own. */
    @Transactional(readOnly = true)
    public List<TeamResponse> list(Actor actor) {
        String base = """
                SELECT t.id, t.name, t.description,
                       (SELECT count(*) FROM team_membership m WHERE m.team_id = t.id) AS members
                FROM team t
                """;
        boolean all = actor.isAdmin() || actor.canCreatePlans();
        return all
                ? jdbc.query(base + " ORDER BY lower(t.name)", (rs, i) -> map(rs))
                : jdbc.query(base + " WHERE t.id IN (SELECT team_id FROM team_membership WHERE user_id = ?) "
                + "ORDER BY lower(t.name)", (rs, i) -> map(rs), actor.id());
    }

    private static TeamResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TeamResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getInt("members"));
    }

    @Transactional(readOnly = true)
    public TeamDetailResponse get(UUID teamId, Actor actor) {
        List<TeamResponse> head = jdbc.query("SELECT id, name, description, 0 AS members FROM team WHERE id = ?",
                (rs, i) -> map(rs), teamId);
        if (head.isEmpty()) {
            throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        }
        boolean member = jdbc.queryForObject(
                "SELECT count(*) FROM team_membership WHERE team_id = ? AND user_id = ?", Integer.class,
                teamId, actor.id()) > 0;
        if (!actor.isAdmin() && !actor.canCreatePlans() && !member) {
            throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        }
        List<TeamMemberResponse> members = jdbc.query("""
                SELECT u.id, u.name, u.email, u.role, m.team_role, m.available, m.escalation_order
                FROM team_membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.team_id = ?
                ORDER BY m.escalation_order NULLS LAST, lower(u.name)
                """, (rs, i) -> new TeamMemberResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("email"), rs.getString("role"), rs.getString("team_role"), rs.getBoolean("available"),
                (Integer) rs.getObject("escalation_order")), teamId);
        TeamResponse h = head.get(0);
        return new TeamDetailResponse(h.id(), h.name(), h.description(), members);
    }

    @Transactional
    public TeamResponse create(Actor actor, CreateTeamRequest req) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO team (id, name, description, created_by) VALUES (?, ?, ?, ?)",
                    id, req.name().trim(), req.description(), actor.id());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TEAM_NAME_TAKEN", "A team with this name already exists");
        }
        audit.record(actor.id(), "TEAM_CREATED", "TEAM", id, null, null, Map.of("name", req.name().trim()));
        return new TeamResponse(id, req.name().trim(), req.description(), 0);
    }

    /** Add a member or change their role / availability / chain position (upsert). */
    @Transactional
    public TeamDetailResponse upsertMember(Actor actor, UUID teamId, TeamMemberRequest req) {
        requireTeam(teamId);
        if (!TEAM_ROLES.contains(req.teamRole())) {
            throw ApiException.badRequest("INVALID_TEAM_ROLE", "teamRole must be LEAD, RESPONDER or MEMBER");
        }
        if (req.escalationOrder() != null && req.escalationOrder() < 1) {
            throw ApiException.badRequest("INVALID_ORDER", "escalationOrder must be 1 or higher");
        }
        List<Map<String, Object>> user = jdbc.queryForList("SELECT role, status FROM app_user WHERE id = ?",
                req.userId());
        if (user.isEmpty()) {
            throw ApiException.badRequest("USER_NOT_FOUND", "User not found");
        }
        if ("DISABLED".equals(user.get(0).get("status"))) {
            throw ApiException.badRequest("USER_DISABLED", "A disabled user cannot join a team");
        }
        if (req.escalationOrder() != null && "VIEWER".equals(user.get(0).get("role"))) {
            throw ApiException.badRequest("VIEWER_NOT_RESPONDER", "A read-only VIEWER cannot be a responder");
        }
        boolean available = req.available() == null || req.available();
        try {
            jdbc.update("""
                    INSERT INTO team_membership (team_id, user_id, team_role, available, escalation_order)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (team_id, user_id) DO UPDATE
                      SET team_role = EXCLUDED.team_role, available = EXCLUDED.available,
                          escalation_order = EXCLUDED.escalation_order
                    """, teamId, req.userId(), req.teamRole(), available, req.escalationOrder());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ORDER_TAKEN", "Another member already holds that escalation position");
        }
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("userId", req.userId());
        after.put("teamRole", req.teamRole());
        after.put("available", available);
        after.put("escalationOrder", req.escalationOrder());
        audit.record(actor.id(), "TEAM_MEMBER_SET", "TEAM", teamId, null, null, after);
        return get(teamId, actor);
    }

    @Transactional
    public TeamDetailResponse removeMember(Actor actor, UUID teamId, UUID userId) {
        requireTeam(teamId);
        int n = jdbc.update("DELETE FROM team_membership WHERE team_id = ? AND user_id = ?", teamId, userId);
        if (n == 0) {
            throw ApiException.notFound("MEMBER_NOT_FOUND", "That user is not in this team");
        }
        audit.record(actor.id(), "TEAM_MEMBER_REMOVED", "TEAM", teamId, null, Map.of("userId", userId), null);
        return get(teamId, actor);
    }

    /** Admin, or the member themself. */
    @Transactional
    public TeamDetailResponse setAvailability(Actor actor, UUID teamId, UUID userId, AvailabilityRequest req) {
        requireTeam(teamId);
        if (!actor.isAdmin() && !actor.id().equals(userId)) {
            throw ApiException.forbidden("FORBIDDEN", "You can only change your own availability");
        }
        int n = jdbc.update("UPDATE team_membership SET available = ? WHERE team_id = ? AND user_id = ?",
                req.available(), teamId, userId);
        if (n == 0) {
            throw ApiException.notFound("MEMBER_NOT_FOUND", "That user is not in this team");
        }
        audit.record(actor.id(), "TEAM_AVAILABILITY_SET", "TEAM", teamId, null, null,
                Map.of("userId", userId, "available", req.available()));
        return get(teamId, actor);
    }

    private void requireTeam(UUID teamId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM team WHERE id = ?", Integer.class, teamId);
        if (n == null || n == 0) {
            throw ApiException.notFound("TEAM_NOT_FOUND", "Team not found");
        }
    }
}
