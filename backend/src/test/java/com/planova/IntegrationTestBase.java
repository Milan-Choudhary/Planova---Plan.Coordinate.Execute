package com.planova;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: the REAL application against a REAL PostgreSQL.
 *
 * <ul>
 *   <li>Requires Docker running (Testcontainers pulls postgres:16-alpine and starts it).</li>
 *   <li>Flyway runs V1..V4 on that fresh database, so every test run also proves the migrations apply.</li>
 *   <li>The container is started ONCE per JVM (static initializer) and shared by all test classes -
 *       much faster than one container per class. Tests isolate themselves with @Transactional rollback.</li>
 *   <li>{@code @DynamicPropertySource} injects the container's random port/credentials, replacing
 *       the .env-driven datasource settings, so tests never touch your development database.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "planova.security.jwt-secret=test-only-secret-that-is-long-enough-0123456789",
        "planova.security.jwt-expiry-minutes=60",
        "planova.security.cors-allowed-origins=http://localhost:5173",
        "planova.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
