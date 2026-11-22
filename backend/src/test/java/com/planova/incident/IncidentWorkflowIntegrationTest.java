package com.planova.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planova.FlowTestSupport;
import com.planova.identity.Role;
import com.planova.identity.User;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The acceptance scenario of the brief, end to end through HTTP + real PostgreSQL:
 * report -> auto-assign -> acknowledge -> resolve, escalation on overdue, no obsolete escalation,
 * idempotent retries, and authorization on direct API calls.
 *
 * STATUS: written but NOT YET EXECUTED (needs Maven + Docker). The same SQL behaviour IS verified
 * against real PostgreSQL by scripts/verify_incident_sql.py.
 */
class IncidentWorkflowIntegrationTest extends FlowTestSupport {

    @Autowired EscalationService escalation;

    private record World(User organizer, User first, User second, UUID team, UUID plan) {
    }

    private World world() {
        User organizer = user(Role.ORGANIZER);
        User first = user(Role.TEAM_MEMBER);
        User second = user(Role.TEAM_MEMBER);
        UUID team = team(first, second);
        return new World(organizer, first, second, team, plan(organizer, team));
    }

    private ResultActions report(World w, User reporter, String title) throws Exception {
        return post(reporter, "/api/v1/plans/" + w.plan() + "/incidents", reportJson(title));
    }

    private String incidentStatus(String incidentId) {
        return jdbc.queryForObject("SELECT status FROM incident WHERE id = ?::uuid", String.class, incidentId);
    }

    @Test
    @DisplayName("reported incident is persisted, auto-assigned to the first responder, audited and notified")
    void reportAssignsAutomatically() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Gate scanner offline").andExpect(status().isCreated()));

        assertThat(incidentStatus(id)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM incident WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(w.first().getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_record WHERE entity_id = ?::uuid "
                + "AND action IN ('INCIDENT_CREATED','INCIDENT_ASSIGNED')", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE incident_id = ?::uuid "
                + "AND recipient_id = ? AND type = 'INCIDENT_ASSIGNED'", Integer.class, id, w.first().getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("repeating a report with the same Idempotency-Key creates exactly one incident")
    void idempotentReport() throws Exception {
        World w = world();
        String url = "/api/v1/plans/" + w.plan() + "/incidents";
        String first = id(postKeyed(w.organizer(), url, "key-1", reportJson("Water leak")).andExpect(status().isCreated()));
        String second = id(postKeyed(w.organizer(), url, "key-1", reportJson("Water leak")).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident WHERE plan_id = ?", Integer.class, w.plan()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("acknowledge then resolve; an overdue sweep afterwards must NOT escalate")
    void resolveBeforeEscalationPreventsEscalation() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Fuse blown"));

        post(w.first(), "/api/v1/incidents/" + id + "/acknowledge", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        post(w.first(), "/api/v1/incidents/" + id + "/resolve", "{\"note\":\"replaced\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        clock.advance(Duration.ofHours(3));
        escalation.escalateDue();

        assertThat(incidentStatus(id)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_assignment WHERE incident_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("overdue acknowledgement escalates once to the next responder; the old assignee is told so")
    void escalationOnOverdue() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Crowd barrier broken"));

        clock.advance(Duration.ofHours(2));
        escalation.escalateDue();
        escalation.escalateDue();                        // a second sweep must change nothing

        assertThat(jdbc.queryForObject("SELECT assignee_id FROM incident WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(w.second().getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_assignment WHERE incident_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_record WHERE entity_id = ?::uuid "
                + "AND action = 'INCIDENT_ESCALATED'", Integer.class, id)).isEqualTo(1);

        post(w.first(), "/api/v1/incidents/" + id + "/acknowledge", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_CURRENT_ASSIGNEE"));
        post(w.second(), "/api/v1/incidents/" + id + "/acknowledge", "{}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("no responder available: incident stays OPEN and administrators are notified")
    void noResponder() throws Exception {
        User admin = user(Role.ADMIN);
        User organizer = user(Role.ORGANIZER);
        UUID emptyTeam = team();
        UUID plan = plan(organizer, emptyTeam);

        String id = id(post(organizer, "/api/v1/plans/" + plan + "/incidents", reportJson("Nobody on call"))
                .andExpect(status().isCreated()));

        assertThat(incidentStatus(id)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE incident_id = ?::uuid "
                + "AND recipient_id = ? AND type = 'NO_ELIGIBLE_RESPONDER'", Integer.class, id, admin.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reject passes the incident on; rejecting at the end of the chain is a 409")
    void rejectChain() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Tent leaking"));

        post(w.first(), "/api/v1/incidents/" + id + "/reject", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(w.second().getId().toString()));
        post(w.second(), "/api/v1/incidents/" + id + "/reject", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_NEXT_RESPONDER"));
        assertThat(incidentStatus(id)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("manual assignment needs an editor and an eligible worker")
    void manualAssignment() throws Exception {
        World w = world();
        User outsider = user(Role.TEAM_MEMBER);
        String id = id(report(w, w.organizer(), "Sound check failed"));
        String url = "/api/v1/incidents/" + id + "/assign";

        post(w.first(), url, "{\"assigneeId\":\"%s\"}".formatted(w.second().getId())).andExpect(status().isForbidden());
        post(w.organizer(), url, "{\"assigneeId\":\"%s\"}".formatted(outsider.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INELIGIBLE_ASSIGNEE"));
        post(w.organizer(), url, "{\"assigneeId\":\"%s\"}".formatted(w.second().getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeId").value(w.second().getId().toString()));
    }

    @Test
    @DisplayName("unauthorized callers are blocked on direct API calls")
    void authorization() throws Exception {
        World w = world();
        User viewer = user(Role.VIEWER);
        User stranger = user(Role.TEAM_MEMBER);
        String id = id(report(w, w.organizer(), "Medical emergency at gate"));

        // read-only role cannot report
        jdbc.update("INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, 'VIEWER')", w.plan(), viewer.getId());
        post(viewer, "/api/v1/plans/" + w.plan() + "/incidents", reportJson("x")).andExpect(status().isForbidden());
        // somebody with no access to the plan cannot even learn the incident exists
        get(stranger, "/api/v1/incidents/" + id).andExpect(status().isNotFound());
        post(stranger, "/api/v1/incidents/" + id + "/acknowledge", "{}").andExpect(status().isNotFound());
        // a plan reader who is not the assignee cannot acknowledge
        post(w.second(), "/api/v1/incidents/" + id + "/acknowledge", "{}").andExpect(status().isForbidden());
        // no token at all
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/incidents/" + id))
                .andExpect(status().isUnauthorized());
        // severity keyword raised the report to CRITICAL
        get(w.organizer(), "/api/v1/incidents/" + id).andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    @DisplayName("reopening a resolved incident hands it to a responder again")
    void reopen() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Fuse blown again"));
        post(w.first(), "/api/v1/incidents/" + id + "/acknowledge", "{}");
        post(w.first(), "/api/v1/incidents/" + id + "/resolve", "{}");
        post(w.organizer(), "/api/v1/incidents/" + id + "/reopen", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
        assertThat(jdbc.queryForObject("SELECT current_sequence FROM incident WHERE id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("audit history is recorded and cannot be altered")
    void auditIsAppendOnly() throws Exception {
        World w = world();
        String id = id(report(w, w.organizer(), "Audit me"));
        get(w.organizer(), "/api/v1/plans/" + w.plan() + "/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM audit_record WHERE entity_id = ?::uuid", id));
    }
}
