package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Story 24.2 — one mapping from a Bref row as JSON (property names of the Bref entities) to the snapshot model, shared
 * by both sources: the export's objects and the database's {@code to_jsonb(row)} — so the two adapters cannot drift.
 * A property the source does not have (an older Bref schema) reads as absent ({@code null} / its Bref default).
 * Timestamps: ISO-8601 with an offset (export), or without (Bref {@code timestamp} columns, UTC).
 */
final class BrefRows {

    private BrefRows() {
    }

    static BrefAccount account(JsonNode n) {
        return new BrefAccount(uuid(n, "id"), text(n, "role"), text(n, "email"), bool(n, "emailVerified", false),
                text(n, "phone"), bool(n, "isVerified", false), text(n, "username"), text(n, "password"),
                text(n, "firstName"), text(n, "lastName"), text(n, "shopId"), bool(n, "isActive", true),
                bool(n, "mustChangePassword", false), text(n, "provider"), text(n, "socialId"),
                instant(n, "createdAt"), instant(n, "updatedAt"), instantOrNull(n, "deletedAt"));
    }

    /** An embedded logo larger than this (base64 text) is refused before decoding (store logos are ≤ 200 KB). */
    static final int MAX_PHOTO_BASE64 = 2 * 1024 * 1024;

    static BrefShop shop(JsonNode n) {
        JsonNode data = n.get("photoData");
        byte[] photo = null;
        if (data != null && !data.isNull()) {
            String base64 = text(data, "base64");
            if (base64 == null) {
                throw new IllegalArgumentException("photoData.base64 missing");
            }
            if (base64.length() > MAX_PHOTO_BASE64) {
                throw new IllegalArgumentException("photoData too large");
            }
            photo = Base64.getDecoder().decode(base64);
        }
        return new BrefShop(uuid(n, "id"), uuid(n, "userId"), text(n, "name"), text(n, "address"),
                text(n, "category"), text(n, "description"), text(n, "phone"), text(n, "photoUrl"), text(n, "niu"),
                text(n, "rccm"), text(n, "country"), bool(n, "isActive", true), instant(n, "createdAt"),
                instant(n, "updatedAt"), instantOrNull(n, "deletedAt"), photo);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean bool(JsonNode n, String field, boolean fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isBoolean()) {
            throw new IllegalArgumentException(field + " is not a boolean");
        }
        return v.booleanValue();
    }

    private static UUID uuid(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return UUID.fromString(v);
    }

    private static Instant instant(JsonNode n, String field) {
        Instant v = instantOrNull(n, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return v;
    }

    static Instant instantOrNull(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null) {
            return null;
        }
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            parsed = LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        }
        // the api-bref export goes through JavaScript dates (milliseconds): the database's microseconds are dropped
        // so both adapters give the same values and the same checksums
        return parsed.truncatedTo(ChronoUnit.MILLIS);
    }
}
