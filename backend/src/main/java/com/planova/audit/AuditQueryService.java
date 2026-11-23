package com.planova.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planova.common.Db;
import com.planova.common.PageResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the audit trail. Authorization is done by the callers (controllers / services). */
@Service
public class AuditQueryService {

    public record Entry(long id, UUID actorId, String actorName, String action, String entityType, UUID entityId,
                        UUID planId, JsonNode before, JsonNode after, String correlationId, Instant createdAt) {
    }

    private static final String SELECT = """
            SELECT a.id, a.actor_id, u.name AS actor_name, a.action, a.entity_type, a.entity_id, a.plan_id,
                   a.before_state::text AS before_state, a.after_state::text AS after_state,
                   a.correlation_id, a.created_at
            FROM audit_record a LEFT JOIN app_user u ON u.id = a.actor_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditQueryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getLong("id"), rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                rs.getString("action"), rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                rs.getObject("plan_id", UUID.class), json(rs.getString("before_state")),
                json(rs.getString("after_state")), rs.getString("correlation_id"), Db.instant(rs, "created_at"));
    }

    private JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** Entries for one entity, oldest first (a timeline). */
    @Transactional(readOnly = true)
    public List<Entry> forEntity(String entityType, UUID entityId) {
        return jdbc.query(SELECT + " WHERE a.entity_type = ? AND a.entity_id = ? ORDER BY a.id",
                (rs, i) -> map(rs), entityType, entityId);
    }

    /** Newest first, optionally limited to one plan and/or one action; plan null = everything (admin only). */
    @Transactional(readOnly = true)
    public PageResponse<Entry> search(UUID planId, String action, int page, int size) {
        int p = Db.clampPage(page);
        int s = Db.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (planId != null) {
            where.append(" AND a.plan_id = ?");
            args.add(planId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = ?");
            args.add(action.trim());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_record a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(p * s);
        List<Entry> items = jdbc.query(SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, p, s, t, (int) ((t + s - 1) / s));
    }
}
