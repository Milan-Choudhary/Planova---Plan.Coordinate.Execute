package com.planova.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Small JDBC conversion helpers. The PostgreSQL driver maps TIMESTAMPTZ to OffsetDateTime but
 * cannot bind java.time.Instant directly, so every timestamp goes through these two methods.
 */
public final class Db {

    private Db() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Clamp client-supplied paging so nobody can request a million rows or a negative page. */
    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    public static int clampPage(int page) {
        return Math.max(page, 0);
    }
}
