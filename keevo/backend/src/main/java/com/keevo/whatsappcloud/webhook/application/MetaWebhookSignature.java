package com.keevo.whatsappcloud.webhook.application;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Story 21.5 — Meta's webhook authentication (Bref {@code isValidSignature} / {@code safeEquals}): every delivery carries
 * {@code X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body with the app secret>}; the subscription check
 * carries the verify token. Both compared in constant time; a missing secret / token or header never matches.
 */
public final class MetaWebhookSignature {

    private static final String PREFIX = "sha256=";

    private MetaWebhookSignature() {
    }

    public static boolean isValid(String appSecret, byte[] rawBody, String header) {
        if (appSecret == null || appSecret.isBlank() || rawBody == null || header == null
                || !header.startsWith(PREFIX) || header.length() != PREFIX.length() + 64) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(header.substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(received, sign(appSecret, rawBody));
    }

    /** {@code sha256=<hex>} of {@code rawBody} (tests and the live check sign deliveries with it). */
    public static String header(String appSecret, byte[] rawBody) {
        return PREFIX + HexFormat.of().formatHex(sign(appSecret, rawBody));
    }

    /** The subscription check: {@code given} equals the configured token (constant time, blank never matches). */
    public static boolean tokenMatches(String given, String expected) {
        if (given == null || expected == null || given.isEmpty() || expected.isEmpty()) {
            return false;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(given.getBytes(StandardCharsets.UTF_8));
            byte[] b = MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static byte[] sign(String appSecret, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
    }
}
