package com.keevo.shared.infrastructure.export;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Typed value converters for export dataset providers (Story v1s-18-2): turn raw native-query
 * values (JDBC / Hibernate types) into the types of {@code ExportColumnType}, and enum codes into
 * French labels.
 */
public final class ExportValues {

    private ExportValues() {
    }

    /** Owner fallback name when the actor has no employee row (same rule as the audit log). */
    public static final String OWNER_LABEL = "Propriétaire";

    public static String str(Object value) {
        return value != null ? value.toString() : null;
    }

    public static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof LocalDateTime l) return l.toInstant(ZoneOffset.UTC);
        if (value instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass());
    }

    public static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDateTime l) return l.toLocalDate();
        if (value instanceof String s) return LocalDate.parse(s);
        throw new IllegalArgumentException("Unsupported date value: " + value.getClass());
    }

    /** Integer XAF amounts and quantities: any number → {@code Long} (decimals rounded half-up). */
    public static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof BigDecimal b) return b.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        if (value instanceof Double || value instanceof Float) return Math.round(((Number) value).doubleValue());
        if (value instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Unsupported numeric value: " + value.getClass());
    }

    public static Boolean toBool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    /** French label of an enum code; unknown codes fall back to the raw code, {@code null} stays null. */
    public static String frLabel(Map<String, String> labels, Object code) {
        if (code == null) return null;
        String c = code.toString();
        return labels.getOrDefault(c, c);
    }

    /**
     * SQL joins resolving the display name of a user id the way the audit adapter does: latest
     * {@code employees} row of that user (LATERAL, {@code LIMIT 1} — never duplicates a row), else
     * « Propriétaire » when {@code public.users.role = 'OWNER'}, else null. Use with
     * {@link #actorNameSelect(String)} and the same alias.
     *
     * @param userIdExpr SQL expression of the user id (e.g. {@code m.actor_id})
     * @param alias      unique alias prefix in the query
     */
    public static String actorJoins(String userIdExpr, String alias) {
        return " LEFT JOIN LATERAL (SELECT NULLIF(TRIM(CONCAT_WS(' ', emp.first_name, emp.last_name)), '') AS name"
                + " FROM employees emp WHERE emp.user_id = " + userIdExpr
                + " ORDER BY emp.updated_at DESC LIMIT 1) " + alias + "_e ON TRUE"
                + " LEFT JOIN public.users " + alias + "_u ON " + alias + "_u.id = " + userIdExpr;
    }

    /** Select expression of the actor name joined by {@link #actorJoins(String, String)}. */
    public static String actorNameSelect(String alias) {
        return "COALESCE(" + alias + "_e.name, CASE WHEN " + alias + "_u.role = 'OWNER' THEN '"
                + OWNER_LABEL + "' END)";
    }
}
