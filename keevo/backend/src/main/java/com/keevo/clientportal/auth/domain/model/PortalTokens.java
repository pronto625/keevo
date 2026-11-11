package com.keevo.clientportal.auth.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Story 21.7 — the client portal session token: {@code kvc_} + 43 base64url characters (32 random bytes from
 * {@link SecureRandom}, 256 bits). Stored as its SHA-256 only (a high-entropy secret needs no slow hash: the lookup is
 * by hash, O(1) on the primary key). Same shape as the MCP access tokens ({@code kvp_}) with its own prefix, so one is
 * never mistaken for the other.
 */
public final class PortalTokens {

    public static final String PREFIX = "kvc_";
    static final int RANDOM_BYTES = 32;
    /** {@code kvc_} + 43 characters (base64url of 32 bytes, no padding). */
    public static final int LENGTH = PREFIX.length() + 43;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private PortalTokens() {
    }

    /** A new clear token (handed once to the client app). */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + ENCODER.encodeToString(bytes);
    }

    /** Whether the text has the shape of a token (cheap check before any database access). */
    public static boolean looksLikeToken(String token) {
        if (token == null || token.length() != LENGTH || !token.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** SHA-256 of the token's UTF-8 bytes (32 bytes). */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
