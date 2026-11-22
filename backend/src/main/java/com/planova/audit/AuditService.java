package com.planova.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planova.common.CorrelationIdFilter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends rows to {@code audit_record} (who did what, to which entity, before/after).
 *
 * <p>{@code Propagation.MANDATORY} is the important annotation here: it REFUSES to run unless the
 * caller already opened a transaction, and then joins it. So "change business data + write the
 * audit row" commit or roll back together (invariant 5 of the brief). A forgotten
 * {@code @Transactional} on a service fails loudly instead of leaving an un-audited change.
 *
 * <p>Plain JdbcTemplate (not JPA): the table is append-only, so we need no entity, no dirty
 * tracking and no accidental updates.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * @param actorId null means "the system" (scheduler / automation)
     * @param before  state before the change as a Map/record, or null for creations
     * @param after   state after the change, or null for deletions. Never put secrets
     *                (password hashes, tokens) in these snapshots.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String entityType, UUID entityId,
                       UUID planId, Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_record
                    (actor_id, action, entity_type, entity_id, plan_id, before_state, after_state, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                actorId, action, entityType, entityId, planId,
                toJson(before), toJson(after), CorrelationIdFilter.current());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Failing to serialise a snapshot is a programming error; abort the whole transaction
            // rather than commit a change with a missing audit trail.
            throw new IllegalStateException("Cannot serialise audit snapshot", e);
        }
    }
}
