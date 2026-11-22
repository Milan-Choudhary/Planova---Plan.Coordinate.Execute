package com.planova.dashboard;

import com.planova.access.AccessService;
import com.planova.identity.Actor;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Headline numbers for the signed-in user. Everything is scoped by the same plan-visibility rule as the lists. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    public record Summary(long plans, long openIncidents, long myAssignedIncidents, long overdueAcknowledgements,
                          long myOpenTasks, long myOverdueTasks, long upcomingEvents, long unreadNotifications) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @GetMapping
    public Summary summary(Authentication auth) {
        Actor a = Actor.of(auth);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime week = now.plusDays(7);
        String vis = AccessService.VISIBLE_PLAN_PREDICATE;
        long plans = count("SELECT count(*) FROM plan p WHERE p.status <> 'ARCHIVED' AND " + vis,
                a.isAdmin(), a.id(), a.id());
        long open = count("SELECT count(*) FROM incident i JOIN plan p ON p.id = i.plan_id WHERE "
                + "i.status NOT IN ('RESOLVED','CLOSED') AND " + vis, a.isAdmin(), a.id(), a.id());
        long mine = count("SELECT count(*) FROM incident WHERE assignee_id = ? "
                + "AND status IN ('ASSIGNED','ACKNOWLEDGED','IN_PROGRESS')", a.id());
        long overdueAck = count("SELECT count(*) FROM incident i JOIN plan p ON p.id = i.plan_id WHERE "
                + "i.status = 'ASSIGNED' AND i.ack_deadline < ? AND " + vis, now, a.isAdmin(), a.id(), a.id());
        long tasks = count("SELECT count(*) FROM task WHERE assignee_id = ? AND status IN ('TODO','IN_PROGRESS')",
                a.id());
        long overdueTasks = count("SELECT count(*) FROM task WHERE assignee_id = ? "
                + "AND status IN ('TODO','IN_PROGRESS') AND deadline < ?", a.id(), now);
        long events = count("SELECT count(*) FROM plan_event e JOIN plan p ON p.id = e.plan_id WHERE "
                + "e.start_at >= ? AND e.start_at < ? AND p.status <> 'ARCHIVED' AND " + vis,
                now, week, a.isAdmin(), a.id(), a.id());
        long unread = count("SELECT count(*) FROM notification WHERE recipient_id = ? AND read_at IS NULL", a.id());
        return new Summary(plans, open, mine, overdueAck, tasks, overdueTasks, events, unread);
    }
}
