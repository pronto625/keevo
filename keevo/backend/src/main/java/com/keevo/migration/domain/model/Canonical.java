package com.keevo.migration.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Story 24.2 — canonical text of a source row ({@code name=value} lines in a fixed order, {@code null} as {@code ∅},
 * instants in ISO-8601 UTC) and its SHA-256: the checksum kept in {@code migration_records}, identical whatever the
 * source adapter (export or database) that produced the row.
 */
public final class Canonical {

    private Canonical() {
    }

    /** @param namesAndValues name, value, name, value… */
    public static String of(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("names and values must come in pairs");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            sb.append(namesAndValues[i]).append('=').append(value == null ? "∅" : value.toString()).append('\n');
        }
        return sb.toString();
    }

    /** Lower-case hex SHA-256 of the UTF-8 text. */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Lower-case hex SHA-256 of bytes (an imported logo). */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
