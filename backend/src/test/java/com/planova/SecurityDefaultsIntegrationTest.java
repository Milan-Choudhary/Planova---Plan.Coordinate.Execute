package com.planova;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.planova.identity.Role;
import com.planova.identity.TokenService;
import com.planova.identity.User;
import com.planova.identity.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Cross-cutting behaviour: health probe, default-deny, error shape for unknown URLs, CORS. NOT YET EXECUTED. */
@Transactional
class SecurityDefaultsIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenService tokens;

    @Test
    @DisplayName("health endpoint is public and reports UP (also proves Flyway migrations applied and DB reachable)")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("actuator endpoints other than health are not exposed")
    void otherActuatorEndpointsClosed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a URL nobody classified is denied by default (401 for anonymous callers)")
    void unknownUrlDeniedByDefault() throws Exception {
        mvc.perform(get("/internal/secret")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("unknown API path for an authenticated user -> 404 in the standard error shape")
    void unknownApiPath() throws Exception {
        User u = users.saveAndFlush(new User("nf@example.test", "NF", encoder.encode("correct-horse-battery"), Role.VIEWER));
        mvc.perform(get("/api/v1/does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(u).value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a client-supplied correlation id is echoed; an unsafe one is replaced")
    void correlationId() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "trace-12345678"))
                .andExpect(header().string("X-Correlation-Id", "trace-12345678"));

        String evil = "bad id\r\nInjected: header";
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", evil))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(result -> {
                    String echoed = result.getResponse().getHeader("X-Correlation-Id");
                    if (evil.equals(echoed)) {
                        throw new AssertionError("unsafe correlation id was echoed");
                    }
                });
    }

    @Test
    @DisplayName("CORS: the configured frontend origin is allowed, others are not")
    void cors() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
