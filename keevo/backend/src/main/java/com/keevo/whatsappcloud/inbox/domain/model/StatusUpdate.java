package com.keevo.whatsappcloud.inbox.domain.model;

import java.util.Locale;

/**
 * Story 21.5 — Meta's delivery status of a sent message ({@code sent}, {@code delivered}, {@code read}, {@code failed}).
 * A status never goes back: {@link #rank} READ 3 &gt; DELIVERED 2 &gt; SENT = FAILED 1 (Bref's {@code STATUS_RANK}); a
 * FAILED after SENT is kept.
 */
public record StatusUpdate(String waMessageId, String status) {

    /** The Keevo status of Meta's value, or {@code null} when unknown. */
    public static String parse(String metaStatus) {
        if (metaStatus == null) {
            return null;
        }
        return switch (metaStatus.toLowerCase(Locale.ROOT)) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed" -> "FAILED";
            default -> null;
        };
    }

    public static int rank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "READ" -> 3;
            case "DELIVERED" -> 2;
            case "SENT", "FAILED" -> 1;
            default -> 0;
        };
    }

    /** Whether {@code next} replaces {@code current} (higher rank, or FAILED after SENT). */
    public static boolean supersedes(String next, String current) {
        return rank(next) > rank(current) || ("FAILED".equals(next) && "SENT".equals(current));
    }
}
