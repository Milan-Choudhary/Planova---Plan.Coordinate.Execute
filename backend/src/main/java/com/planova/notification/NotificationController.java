package com.planova.notification;

import com.planova.common.ApiException;
import com.planova.common.Db;
import com.planova.common.PageResponse;
import com.planova.identity.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A user's own inbox. Every query filters on the caller's id: nobody can read another user's notifications. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationResponse(UUID id, UUID incidentId, String type, String title, String body,
                                       String status, Instant createdAt, Instant readAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(Authentication auth,
                                                   @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Actor actor = Actor.of(auth);
        int p = Db.clampPage(page);
        int s = Db.clampSize(size);
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        List<NotificationResponse> items = jdbc.query(
                "SELECT id, incident_id, type, title, body, status, created_at, read_at FROM notification "
                        + "WHERE recipient_id = ?" + filter + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new NotificationResponse(rs.getObject("id", UUID.class),
                        rs.getObject("incident_id", UUID.class), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getString("status"), Db.instant(rs, "created_at"),
                        Db.instant(rs, "read_at")),
                actor.id(), s, p * s);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE recipient_id = ?" + filter, Long.class, actor.id());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, p, s, t, (int) ((t + s - 1) / s));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Authentication auth) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE recipient_id = ? AND read_at IS NULL",
                Long.class, Actor.of(auth).id());
        return Map.of("count", n == null ? 0 : n);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(Authentication auth, @PathVariable UUID id) {
        // COALESCE keeps the first read time, so repeating the call is harmless (idempotent).
        int n = jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, ?) "
                + "WHERE id = ? AND recipient_id = ?", Db.ts(clock.instant()), id, Actor.of(auth).id());
        if (n == 0) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication auth) {
        int n = jdbc.update("UPDATE notification SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL",
                Db.ts(clock.instant()), Actor.of(auth).id());
        return Map.of("updated", n);
    }
}
