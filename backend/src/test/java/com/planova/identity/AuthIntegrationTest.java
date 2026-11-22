package com.planova.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planova.IntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication + authorization tests through the real HTTP layer (MockMvc) and real PostgreSQL.
 *
 * <p>{@code @Transactional} on the class: each test runs in a transaction that is rolled back
 * afterwards, so tests cannot leak users into each other. (MockMvc executes on the test's thread,
 * so the application code joins the same transaction.)
 *
 * STATUS: written but NOT YET EXECUTED (no Maven Central access in the authoring sandbox).
 */
@Transactional
class AuthIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenService tokens;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;

    // ---------------------------------------------------------------- helpers

    private User createUser(String email, Role role) {
        return users.saveAndFlush(new User(email, "Test " + role, encoder.encode(PASSWORD), role));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.issue(user).value();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions createUserAs(User actor, String json) throws Exception {
        return mvc.perform(post("/api/v1/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String tokenWith(String issuer, UUID subject, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(subject.toString()).issuedAt(issuedAt).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    // ---------------------------------------------------------------- login

    @Test
    @DisplayName("login with valid credentials returns a token and the user, never the password hash")
    void loginSucceeds() throws Exception {
        createUser("viewer@example.test", Role.VIEWER);

        login("viewer@example.test", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.user.role").value("VIEWER"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    @DisplayName("login email is case-insensitive")
    void loginIsCaseInsensitive() throws Exception {
        createUser("mixed@example.test", Role.VIEWER);
        login("MIXED@Example.TEST", PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("wrong password and unknown email give the SAME 401 body (no account enumeration)")
    void loginFailuresAreIndistinguishable() throws Exception {
        createUser("real@example.test", Role.VIEWER);

        String wrongPassword = login("real@example.test", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@example.test", "whatever-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        // Compare the messages (timestamps/correlation ids legitimately differ).
        assertThat(messageOf(wrongPassword)).isEqualTo(messageOf(unknownEmail));
    }

    private static String messageOf(String json) {
        int i = json.indexOf("\"message\":\"");
        return json.substring(i, json.indexOf('"', i + 11 + 1));
    }

    @Test
    @DisplayName("a DISABLED user cannot log in")
    void disabledUserCannotLogin() throws Exception {
        User u = createUser("off@example.test", Role.VIEWER);
        u.changeStatus(UserStatus.DISABLED);
        users.saveAndFlush(u);

        login("off@example.test", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("login with a blank body field is a 400 validation error with field details")
    void loginValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("email")));
    }

    @Test
    @DisplayName("malformed JSON is a 400, not a 500")
    void malformedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---------------------------------------------------------------- token handling

    @Test
    @DisplayName("protected endpoint without a token -> 401 in the standard error shape")
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("/me returns the current user")
    void meReturnsCurrentUser() throws Exception {
        User u = createUser("me@example.test", Role.ORGANIZER);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.test"))
                .andExpect(jsonPath("$.role").value("ORGANIZER"));
    }

    @Test
    @DisplayName("a token with a tampered signature is rejected")
    void tamperedTokenRejected() throws Exception {
        User u = createUser("t@example.test", Role.ADMIN);
        String token = tokens.issue(u).value();
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenRejected() throws Exception {
        User u = createUser("old@example.test", Role.ADMIN);
        Instant now = Instant.now();
        String expired = tokenWith("planova-ai", u.getId(), now.minus(3, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a correctly signed token from a different issuer is rejected")
    void wrongIssuerRejected() throws Exception {
        User u = createUser("iss@example.test", Role.ADMIN);
        Instant now = Instant.now();
        String foreign = tokenWith("someone-else", u.getId(), now, now.plus(1, ChronoUnit.HOURS));
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token for a user who is disabled AFTER login stops working immediately")
    void disabledAfterLoginTokenDies() throws Exception {
        User u = createUser("later@example.test", Role.ORGANIZER);
        String auth = bearer(u);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isOk());

        u.changeStatus(UserStatus.DISABLED);
        users.saveAndFlush(u);

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("role comes from the database, not the token: a demoted admin's old token loses admin rights at once")
    void roleClaimIsNotTrusted() throws Exception {
        User u = createUser("demoted@example.test", Role.ADMIN);
        String auth = bearer(u);   // token claims role=ADMIN
        mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isOk());

        u.changeRole(Role.VIEWER);
        users.saveAndFlush(u);

        mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- authorization by direct API call

    @Test
    @DisplayName("non-admin roles calling the admin API directly get 403")
    void nonAdminsForbidden() throws Exception {
        for (Role role : new Role[] {Role.VIEWER, Role.TEAM_MEMBER, Role.ORGANIZER}) {
            User u = createUser(role.name().toLowerCase() + "@example.test", role);
            mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(u)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            createUserAs(u, "{\"email\":\"x@example.test\",\"name\":\"X\",\"role\":\"ADMIN\",\"password\":\"twelve-chars-ok\"}")
                    .andExpect(status().isForbidden());
        }
        assertThat(users.existsByEmail("x@example.test")).isFalse();   // nothing was created
    }

    @Test
    @DisplayName("unauthenticated call to the admin API -> 401")
    void adminApiWithoutToken() throws Exception {
        mvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- admin user management

    @Test
    @DisplayName("admin creates a user: 201, email normalised, password stored as BCrypt, audit row written")
    void adminCreatesUser() throws Exception {
        User admin = createUser("admin@example.test", Role.ADMIN);

        createUserAs(admin, "{\"email\":\"  New.Person@Example.TEST \",\"name\":\" New Person \","
                + "\"role\":\"TEAM_MEMBER\",\"password\":\"a-long-enough-password\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/admin/users/")))
                .andExpect(jsonPath("$.email").value("new.person@example.test"))
                .andExpect(jsonPath("$.name").value("New Person"))
                .andExpect(jsonPath("$.role").value("TEAM_MEMBER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User saved = users.findByEmail("new.person@example.test").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2");                       // BCrypt, not plaintext
        assertThat(saved.getPasswordHash()).doesNotContain("a-long-enough-password");

        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_record WHERE action = 'USER_CREATED' AND entity_id = ? AND actor_id = ?",
                Integer.class, saved.getId(), admin.getId());
        assertThat(audits).isEqualTo(1);
        // The audit snapshot must not contain the password hash.
        String after = jdbc.queryForObject(
                "SELECT after_state::text FROM audit_record WHERE entity_id = ?", String.class, saved.getId());
        assertThat(after).doesNotContain("$2").doesNotContain("password");

        // The new user can actually log in.
        login("new.person@example.test", "a-long-enough-password").andExpect(status().isOk());
    }

    @Test
    @DisplayName("creating a user whose email differs only by case -> 409 EMAIL_TAKEN")
    void duplicateEmailConflict() throws Exception {
        User admin = createUser("admin2@example.test", Role.ADMIN);
        createUser("dup@example.test", Role.VIEWER);

        createUserAs(admin, "{\"email\":\"DUP@example.test\",\"name\":\"Dup\",\"role\":\"VIEWER\",\"password\":\"a-long-enough-password\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    @DisplayName("invalid create-user body -> 400 with per-field errors")
    void createUserValidation() throws Exception {
        User admin = createUser("admin3@example.test", Role.ADMIN);

        createUserAs(admin, "{\"email\":\"not-an-email\",\"name\":\"\",\"password\":\"short\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("name")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("role")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("password")));
    }

    @Test
    @DisplayName("a password under 72 characters but over 72 BYTES is rejected (BCrypt truncation)")
    void multiByteLongPassword() throws Exception {
        User admin = createUser("admin4@example.test", Role.ADMIN);
        String longBytes = "é".repeat(40);   // 40 characters, 80 bytes

        createUserAs(admin, "{\"email\":\"mb@example.test\",\"name\":\"MB\",\"role\":\"VIEWER\",\"password\":\"" + longBytes + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    @DisplayName("admin changes another user's role; the change is audited with before/after")
    void adminUpdatesRole() throws Exception {
        User admin = createUser("admin5@example.test", Role.ADMIN);
        User target = createUser("target@example.test", Role.VIEWER);

        mvc.perform(patch("/api/v1/admin/users/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORGANIZER"));

        String before = jdbc.queryForObject(
                "SELECT before_state->>'role' FROM audit_record WHERE action='USER_UPDATED' AND entity_id=?",
                String.class, target.getId());
        String after = jdbc.queryForObject(
                "SELECT after_state->>'role' FROM audit_record WHERE action='USER_UPDATED' AND entity_id=?",
                String.class, target.getId());
        assertThat(before).isEqualTo("VIEWER");
        assertThat(after).isEqualTo("ORGANIZER");
    }

    @Test
    @DisplayName("an admin cannot change their own role or status")
    void adminCannotModifySelf() throws Exception {
        User admin = createUser("self@example.test", Role.ADMIN);
        mvc.perform(patch("/api/v1/admin/users/" + admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
    }

    @Test
    @DisplayName("updating an unknown user -> 404; empty update -> 400")
    void updateEdgeCases() throws Exception {
        User admin = createUser("admin6@example.test", Role.ADMIN);
        User target = createUser("t2@example.test", Role.VIEWER);

        mvc.perform(patch("/api/v1/admin/users/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mvc.perform(patch("/api/v1/admin/users/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_UPDATE"));
    }

    @Test
    @DisplayName("an invalid role value in the body is a 400, not a 500")
    void invalidEnumValue() throws Exception {
        User admin = createUser("admin7@example.test", Role.ADMIN);
        User target = createUser("t3@example.test", Role.VIEWER);
        mvc.perform(patch("/api/v1/admin/users/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("user listing is paginated and page size is clamped")
    void listIsPaginatedAndClamped() throws Exception {
        User admin = createUser("admin8@example.test", Role.ADMIN);
        mvc.perform(get("/api/v1/admin/users?page=0&size=100000").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items").isArray());
    }
}
