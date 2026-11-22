package com.planova.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one demo user per role so the app is usable right after setup.
 *
 * <p>{@code @Profile("dev")}: this bean does not exist unless SPRING_PROFILES_ACTIVE contains "dev",
 * so production can never ship demo accounts. The shared password comes from SEED_PASSWORD in .env -
 * no password is hard-coded anywhere in source control. Idempotent: existing emails are skipped.
 */
@Component
@Profile("dev")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Demo(String email, String name, Role role) {
    }

    private static final List<Demo> DEMO_USERS = List.of(
            new Demo("admin@planova.local", "Demo Admin", Role.ADMIN),
            new Demo("organizer@planova.local", "Demo Organizer", Role.ORGANIZER),
            new Demo("member@planova.local", "Demo Team Member", Role.TEAM_MEMBER),
            new Demo("member2@planova.local", "Demo Second Responder", Role.TEAM_MEMBER),
            new Demo("viewer@planova.local", "Demo Viewer", Role.VIEWER));

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;
    private final JdbcTemplate jdbc;

    public DemoDataSeeder(UserRepository users, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
                          @Value("${planova.seed.password:}") String seedPassword) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedPassword == null || seedPassword.length() < 12) {
            log.warn("Demo users NOT created: set SEED_PASSWORD (>= 12 characters) in .env to enable them");
            return;
        }
        String hash = passwordEncoder.encode(seedPassword);   // one hash reused: they share a password
        for (Demo d : DEMO_USERS) {
            if (!users.existsByEmail(d.email())) {
                users.saveAndFlush(new User(d.email(), d.name(), hash, d.role()));
                log.info("Created demo user {} ({})", d.email(), d.role());
            }
        }
        seedOperations();
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = ?", UUID.class, email);
    }

    /**
     * A ready-made team (escalation chain: member -> member2 -> organizer) and plan so the incident
     * flow can be tried immediately: log in as the organizer, report an incident, watch it get assigned.
     */
    private void seedOperations() {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM team WHERE name = 'Operations Response'",
                Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        UUID organizer = userId("organizer@planova.local");
        UUID team = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, name, description, created_by) VALUES (?, 'Operations Response', "
                + "'On-site responders for demo events', ?)", team, userId("admin@planova.local"));
        String[][] chain = {{"member@planova.local", "RESPONDER", "1"}, {"member2@planova.local", "RESPONDER", "2"},
                {"organizer@planova.local", "LEAD", "3"}};
        for (String[] m : chain) {
            jdbc.update("INSERT INTO team_membership (team_id, user_id, team_role, escalation_order) VALUES (?, ?, ?, ?)",
                    team, userId(m[0]), m[1], Integer.parseInt(m[2]));
        }
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO plan (id, title, description, owner_id, team_id, status) VALUES (?, "
                + "'Demo Summer Fest', 'Sample plan for trying the incident workflow', ?, ?, 'ACTIVE')",
                plan, organizer, team);
        jdbc.update("INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, 'OWNER')", plan, organizer);
        jdbc.update("INSERT INTO plan_member (plan_id, user_id, plan_role) VALUES (?, ?, 'VIEWER')", plan,
                userId("viewer@planova.local"));
        log.info("Created demo team 'Operations Response' and plan 'Demo Summer Fest'");
    }
}
