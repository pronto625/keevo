package com.keevo.shared.infrastructure.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Opaque N-column keyset cursor of the export datasets (Story v1s-18-2): each typed value is
 * written as text, Base64URL-encoded, and the parts are joined with {@code '.'} (so text values
 * may contain any character). Decoding checks the arity and the type of every part.
 */
public final class KeysetCursor {

    /** Type of one keyset column — drives encoding and SQL parameter casting. */
    public enum KeyType {
        /** {@code timestamptz} — {@link Instant}. */
        TIMESTAMP("timestamptz"),
        /** {@code date} — {@link LocalDate}. */
        DATE("date"),
        /** {@code text} — {@link String}. */
        TEXT("text"),
        /** {@code uuid} — {@link UUID}. */
        UUID("uuid");

        private final String sqlType;

        KeyType(String sqlType) {
            this.sqlType = sqlType;
        }

        public String sqlType() {
            return sqlType;
        }
    }

    private static final int MAX_LENGTH = 4096;

    private KeysetCursor() {
    }

    /** Encode typed values (never null) into an opaque cursor. */
    public static String encode(List<KeyType> types, List<Object> values) {
        if (types.size() != values.size()) {
            throw new IllegalArgumentException("Keyset arity mismatch");
        }
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            Object v = values.get(i);
            if (v == null) {
                throw new IllegalStateException("Keyset value " + i + " is null — keyset expressions must be non-null");
            }
            if (i > 0) sb.append('.');
            sb.append(enc.encodeToString(v.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return sb.toString();
    }

    /**
     * Decode a cursor produced by {@link #encode}.
     *
     * @throws IllegalArgumentException when the cursor is malformed (arity, encoding or type)
     */
    public static List<Object> decode(List<KeyType> types, String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Malformed export cursor");
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != types.size()) {
            throw new IllegalArgumentException("Malformed export cursor");
        }
        List<Object> values = new ArrayList<>(parts.length);
        try {
            for (int i = 0; i < parts.length; i++) {
                String raw = new String(Base64.getUrlDecoder().decode(parts[i]), StandardCharsets.UTF_8);
                values.add(switch (types.get(i)) {
                    case TIMESTAMP -> Instant.parse(raw);
                    case DATE -> LocalDate.parse(raw);
                    case TEXT -> raw;
                    case UUID -> java.util.UUID.fromString(raw);
                });
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed export cursor", e);
        }
        return values;
    }

    /** JDBC-bindable parameter value of a decoded key (bound inside {@code CAST(:p AS <sqlType>)}). */
    static Object toParameter(KeyType type, Object value) {
        return switch (type) {
            case TIMESTAMP -> java.sql.Timestamp.from((Instant) value);
            case DATE -> java.sql.Date.valueOf((LocalDate) value);
            case TEXT -> value;
            case UUID -> value.toString();
        };
    }

    /** Typed key value read back from a result row. */
    static Object fromColumn(KeyType type, Object raw) {
        return switch (type) {
            case TIMESTAMP -> ExportValues.toInstant(raw);
            case DATE -> ExportValues.toLocalDate(raw);
            case TEXT -> ExportValues.str(raw);
            case UUID -> raw == null ? null : (raw instanceof UUID u ? u : java.util.UUID.fromString(raw.toString()));
        };
    }
}
