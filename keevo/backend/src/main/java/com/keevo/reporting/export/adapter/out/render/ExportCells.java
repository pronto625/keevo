package com.keevo.reporting.export.adapter.out.render;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared cell conventions of the export renderers (Story v1s-18-1): amounts are integer XAF,
 * dates and datetimes are rendered in WAT ({@code Africa/Douala}). CSV text cells that a
 * spreadsheet would read as a formula are neutralised with a leading apostrophe
 * ({@link #safeText}); XLSX string cells are never evaluated and are written as-is.
 */
final class ExportCells {

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private ExportCells() {
    }

    /** Numbers, signed numbers and phone numbers ({@code +237 6 99 00 00 00}) are not formulas. */
    private static final java.util.regex.Pattern NUMERIC_LIKE = java.util.regex.Pattern.compile("^[+-]?[\\d\\s().]+$");

    /**
     * CSV formula-injection guard: text starting with {@code = + - @ TAB CR} gets a leading
     * {@code '}, unless it is numeric-like (phone numbers, signed numbers).
     */
    static String safeText(String s) {
        if (s == null || s.isEmpty()) return s;
        char c = s.charAt(0);
        boolean risky = c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
        return risky && !NUMERIC_LIKE.matcher(s).matches() ? "'" + s : s;
    }

    /** Integer value of a numeric cell (amounts rounded half-up to the unit). */
    static long toLong(Object v) {
        if (v instanceof BigDecimal bd) return bd.setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (v instanceof Double || v instanceof Float) return Math.round(((Number) v).doubleValue());
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString().trim());
    }

    static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Long || v instanceof Integer || v instanceof Short) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString().trim());
    }

    static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    /** WAT calendar date of a DATE cell ({@code LocalDate}, or an {@code Instant}). */
    static LocalDate toWatDate(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof LocalDateTime l) return l.toLocalDate();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        Instant i = asInstant(v);
        return i != null ? i.atZone(WAT).toLocalDate() : LocalDate.parse(v.toString());
    }

    /** WAT wall-clock time of a DATETIME cell ({@code Instant}). */
    static LocalDateTime toWatDateTime(Object v) {
        if (v instanceof LocalDateTime l) return l;
        if (v instanceof LocalDate d) return d.atStartOfDay();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        Instant i = asInstant(v);
        return LocalDateTime.ofInstant(i != null ? i : Instant.parse(v.toString()), WAT);
    }

    /** Instant of an absolute time value, or {@code null} when {@code v} is not one. */
    private static Instant asInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof java.time.ZonedDateTime z) return z.toInstant();
        if (v instanceof java.util.Date d && !(v instanceof java.sql.Date)) return d.toInstant(); // incl. Timestamp
        return null;
    }
}
