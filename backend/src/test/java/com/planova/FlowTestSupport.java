package com.planova;


import com.jayway.jsonpath.JsonPath;
import com.planova.identity.Role;
import com.planova.identity.TokenService;
import com.planova.identity.User;
import com.planova.identity.UserRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Shared helpers for the workflow tests. These tests deliberately do NOT use {@code @Transactional}:
 * the incident workflow starts in an AFTER_COMMIT listener, which only runs if the request's
 * transaction really commits. Isolation comes from unique emails / one fresh team + plan per test.
 *
 * STATUS: written but NOT YET EXECUTED (needs Maven + Docker, unavailable in the authoring sandbox).
 */
public abstract class FlowTestSupport extends IntegrationTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected TokenService tokens;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TestClockConfig.MutableClock clock;

    protected User user(Role role) {
        return users.saveAndFlush(new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.test",
                "T " + role, encoder.encode("correct-horse-battery"), role));
    }

    protected String bearer(User u) {
        return "Bearer " + tokens.issue(u).value();
    }

    protected ResultActions post(User u, String url, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions postKeyed(User u, String url, String key, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", bearer(u)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions patch(User u, String url, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch(url).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions get(User u, String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", bearer(u)));
    }

    protected static String id(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id");
    }

    protected static String field(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(v);
    }

    /** A team whose responders hold chain positions 1..n in the order given. */
    protected UUID team(User... orderedResponders) {
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, name) VALUES (?, ?)", team, "team-" + team);
        int order = 1;
        for (User u : orderedResponders) {
            jdbc.update("INSERT INTO team_membership (team_id, user_id, team_role, escalation_order) "
                    + "VALUES (?, ?, 'RESPONDER', ?)", team, u.getId(), order++);
        }
        return team;
    }

    /** A plan owned by {@code owner}, handled by {@code team}; returns the plan id. */
    protected UUID plan(User owner, UUID teamId) {
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO plan (id, title, owner_id, team_id, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                plan, "plan-" + plan, owner.getId(), teamId);
        jdbc.update("INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, 'OWNER')", plan, owner.getId());
        return plan;
    }

    protected String reportJson(String title) {
        return "{\"title\":\"%s\",\"description\":\"details\",\"severity\":\"MEDIUM\"}".formatted(title);
    }
}
