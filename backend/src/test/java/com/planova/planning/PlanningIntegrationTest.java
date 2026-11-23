package com.planova.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planova.FlowTestSupport;
import com.planova.identity.Role;
import com.planova.identity.User;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plans, events, tasks and their permissions. STATUS: written but NOT YET EXECUTED (needs Maven + Docker). */
class PlanningIntegrationTest extends FlowTestSupport {

    @Test
    @DisplayName("organizer creates a plan, an event and a task assigned to a team member")
    void organizerPlansAnEvent() throws Exception {
        User organizer = user(Role.ORGANIZER);
        User member = user(Role.TEAM_MEMBER);
        UUID team = team(member);
        jdbc.update("UPDATE team SET created_by = ? WHERE id = ?", organizer.getId(), team);

        String planId = id(post(organizer, "/api/v1/plans", "{\"title\":\"Summer Fest\",\"teamId\":\"%s\"}".formatted(team))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.myPlanRole").value("OWNER")));
        post(organizer, "/api/v1/plans/" + planId + "/events",
                "{\"title\":\"Opening\",\"startAt\":\"2030-06-01T10:00:00Z\",\"endAt\":\"2030-06-01T12:00:00Z\"}")
                .andExpect(status().isCreated());
        String taskId = id(post(organizer, "/api/v1/plans/" + planId + "/tasks",
                "{\"title\":\"Set up stage\",\"assigneeId\":\"%s\"}".formatted(member.getId()))
                .andExpect(status().isCreated()));

        get(member, "/api/v1/tasks/mine").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ? AND type = 'TASK_ASSIGNED'",
                Integer.class, member.getId())).isEqualTo(1);
        assertThat(taskId).isNotBlank();
    }

    @Test
    void organizerCannotReadOrAttachAnotherOrganizersTeam() throws Exception {
        User owner = user(Role.ORGANIZER);
        User other = user(Role.ORGANIZER);
        User admin = user(Role.ADMIN);
        String team = id(post(owner, "/api/v1/teams", "{\"name\":\"Ceremony-" + UUID.randomUUID() + "\"}")
                .andExpect(status().isCreated()));
        get(other, "/api/v1/teams/" + team).andExpect(status().isNotFound());
        post(other, "/api/v1/plans", "{\"title\":\"Wedding\",\"teamId\":\"%s\"}".formatted(team))
                .andExpect(status().isNotFound());
        get(owner, "/api/v1/teams/" + team).andExpect(status().isOk());
        get(admin, "/api/v1/teams/" + team).andExpect(status().isOk());
    }

    @Test
    @DisplayName("viewers cannot create plans; strangers get 404 for a plan they cannot read")
    void permissions() throws Exception {
        User organizer = user(Role.ORGANIZER);
        User viewer = user(Role.VIEWER);
        User stranger = user(Role.TEAM_MEMBER);
        String planId = id(post(organizer, "/api/v1/plans", "{\"title\":\"Private\"}").andExpect(status().isCreated()));

        post(viewer, "/api/v1/plans", "{\"title\":\"nope\"}").andExpect(status().isForbidden());
        get(stranger, "/api/v1/plans/" + planId).andExpect(status().isNotFound());
        get(stranger, "/api/v1/plans/" + planId + "/tasks").andExpect(status().isNotFound());
        post(stranger, "/api/v1/plans/" + planId + "/tasks", "{\"title\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an assignee may change only the status of their task; illegal transitions are 409")
    void taskPermissionsAndStateMachine() throws Exception {
        User organizer = user(Role.ORGANIZER);
        User member = user(Role.TEAM_MEMBER);
        UUID plan = plan(organizer, team(member));
        String taskId = id(post(organizer, "/api/v1/plans/" + plan + "/tasks",
                "{\"title\":\"Lights\",\"assigneeId\":\"%s\"}".formatted(member.getId())).andExpect(status().isCreated()));

        patch(member, "/api/v1/tasks/" + taskId, "{\"title\":\"renamed\"}").andExpect(status().isForbidden());
        patch(member, "/api/v1/tasks/" + taskId, "{\"status\":\"DONE\"}")            // TODO -> DONE is not allowed
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        patch(member, "/api/v1/tasks/" + taskId, "{\"status\":\"IN_PROGRESS\"}").andExpect(status().isOk());
        patch(member, "/api/v1/tasks/" + taskId, "{\"status\":\"DONE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("tasks cannot be assigned to ineligible users; events must have end after start")
    void validation() throws Exception {
        User organizer = user(Role.ORGANIZER);
        User outsider = user(Role.TEAM_MEMBER);
        UUID plan = plan(organizer, team());

        post(organizer, "/api/v1/plans/" + plan + "/tasks",
                "{\"title\":\"x\",\"assigneeId\":\"%s\"}".formatted(outsider.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INELIGIBLE_ASSIGNEE"));
        post(organizer, "/api/v1/plans/" + plan + "/events",
                "{\"title\":\"bad\",\"startAt\":\"2030-06-01T12:00:00Z\",\"endAt\":\"2030-06-01T10:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIMES"));
    }

    @Test
    @DisplayName("a plan with unresolved incidents cannot be archived; once resolved it can")
    void archiveGuard() throws Exception {
        User organizer = user(Role.ORGANIZER);
        User responder = user(Role.TEAM_MEMBER);
        UUID plan = plan(organizer, team(responder));
        String incident = id(post(organizer, "/api/v1/plans/" + plan + "/incidents", reportJson("Open one"))
                .andExpect(status().isCreated()));

        post(organizer, "/api/v1/plans/" + plan + "/status", "{\"status\":\"ARCHIVED\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPEN_INCIDENTS"));

        post(responder, "/api/v1/incidents/" + incident + "/acknowledge", "{}").andExpect(status().isOk());
        post(responder, "/api/v1/incidents/" + incident + "/resolve", "{}").andExpect(status().isOk());
        post(organizer, "/api/v1/plans/" + plan + "/status", "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
        post(organizer, "/api/v1/plans/" + plan + "/tasks", "{\"title\":\"late\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_ARCHIVED"));
    }
}
