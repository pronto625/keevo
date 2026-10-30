package com.keevo.shared.infrastructure.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Value formatting of the PDF documents (Story v1s-18-3): integer XAF amounts grouped with a
 * non-breaking space ({@code 12 500 XAF}), dates and times in WAT ({@code Africa/Douala}).
 * Story 14.2: core helper shared by the export documents and the invoices.
 */
public final class PdfFormats {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");
    public static final char NBSP = ' ';
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private PdfFormats() {
    }

    /** {@code 12500} → {@code "12 500 XAF"} (non-breaking spaces). */
    public static String xaf(long amount) {
        return integer(amount) + NBSP + "XAF";
    }

    /** {@code 12500} → {@code "12 500"} (non-breaking space as thousands separator). */
    public static String integer(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        int lead = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) sb.append(NBSP);
            sb.append(digits.charAt(i));
        }
        return value < 0 ? "-" + sb : sb.toString();
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    public static String date(Instant i) {
        return i == null ? "" : i.atZone(WAT).format(DATE);
    }

    public static String time(Instant i) {
        return i == null ? "" : i.atZone(WAT).format(TIME);
    }

    public static String dateTime(Instant i) {
        return i == null ? "" : i.atZone(WAT).format(DATE_TIME);
    }

    /** Integer value of a provider cell (amounts rounded half-up), {@code null} stays null. */
    public static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd.setScale(0, RoundingMode.HALF_UP).longValue();
        if (v instanceof Double || v instanceof Float) return Math.round(((Number) v).doubleValue());
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString().trim());
    }

    public static long toLongOrZero(Object v) {
        Long l = toLong(v);
        return l != null ? l : 0L;
    }

    /** Absolute instant of a provider DATETIME cell. */
    public static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (v instanceof java.time.ZonedDateTime z) return z.toInstant();
        if (v instanceof LocalDateTime l) return l.atZone(WAT).toInstant();
        if (v instanceof java.util.Date d && !(v instanceof java.sql.Date)) return d.toInstant();
        return Instant.parse(v.toString());
    }

    /** Characters XML 1.0 forbids: they would survive th:text and break the XHTML parse. */
    private static final java.util.regex.Pattern XML_ILLEGAL =
            java.util.regex.Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");

    public static String text(Object v) {
        return v == null ? "" : XML_ILLEGAL.matcher(v.toString()).replaceAll("");
    }
}
