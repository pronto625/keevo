package com.keevo.admin.crosstenant;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Story 20.13 — opaque keyset cursor of the cross-account lists: the sort-key values of the last row, the tenant id
 * that breaks ties between schemas, and a digest of the filters it was issued for (reusing it with other filters is
 * refused, as in 20.10). Format (base64url, no padding): {@code v1 ␟ filterDigest ␟ tenantId ␟ key0 ␟ key1 …}.
 */
public record KeysetCursor(String tenantId, List<String> keys) {

    static final String VERSION = "v1";
    static final String SEPARATOR = "\u001f";
    static final int MAX_LENGTH = 1024;
    /** Tenant slot of cursors over public tables (users). */
    public static final String PLATFORM_SLOT = "public";
    static final String INVALID = "Curseur invalide : relancez la liste depuis le début";

    public KeysetCursor {
        keys = List.copyOf(keys);
    }

    /** Encodes the cursor of the row whose keys and tenant are given, for the filters described by {@code filters}. */
    public static String encode(String filters, String tenantId, List<String> keys) {
        List<String> parts = new ArrayList<>();
        parts.add(VERSION);
        parts.add(digest(filters));
        parts.add(tenantId);
        parts.addAll(keys);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor issued for the same filters and sort; {@code null} / blank → null (first page). Anything else —
     * other filters, wrong key count, a key that is not a value of its type ({@link KeysetSort.Key#valid}), a tenant
     * slot that is neither a UUID nor {@code public} — → 422 {@code VALIDATION_ERROR} « Curseur invalide », never a
     * SQL error.
     */
    public static KeysetCursor decode(String cursor, String filters, KeysetSort sort) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String[] parts = raw.split(SEPARATOR, -1);
        if (parts.length != 3 + sort.size() || !VERSION.equals(parts[0]) || !digest(filters).equals(parts[1])
                || Arrays.stream(parts).anyMatch(String::isEmpty) || !validTenant(parts[2])) {
            throw invalid();
        }
        List<String> keys = List.of(parts).subList(3, parts.length);
        if (!sort.valid(keys)) {
            throw invalid();
        }
        return new KeysetCursor(parts[2], keys);
    }

    private static boolean validTenant(String slot) {
        if (PLATFORM_SLOT.equals(slot)) {
            return true;
        }
        try {
            java.util.UUID.fromString(slot);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static DomainException invalid() {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, INVALID);
    }

    static String digest(String filters) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((filters == null ? "" : filters).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
