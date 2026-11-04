package com.keevo.messaging.channel.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Story 19.9 — recipient normalization and log masking of the SMS / email channels. A log line never carries a full
 * phone or address: phones keep their last 3 digits, emails their first letter and domain.
 */
public final class Recipients {

    private static final Pattern E164 = Pattern.compile("^\\+\\d{8,15}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s.\\-()]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");
    private static final int MAX_SENDER_ID = 11;

    private Recipients() {
    }

    /** Spaces, dashes, dots and parentheses stripped, a leading {@code 00} read as {@code +}; empty unless the result matches {@code ^\+\d{8,15}$}. */
    public static Optional<String> normalizePhone(String phone) {
        if (phone == null) return Optional.empty();
        String cleaned = PHONE_SEPARATORS.matcher(phone).replaceAll("");
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2); // international call prefix
        }
        return E164.matcher(cleaned).matches() ? Optional.of(cleaned) : Optional.empty();
    }

    /**
     * Non-alphanumerics stripped, at most 11 characters; {@code fallback} (itself normalized, possibly null) when
     * nothing is left.
     */
    public static String normalizeSenderId(String senderId, String fallback) {
        String cleaned = stripSenderId(senderId);
        return cleaned != null ? cleaned : stripSenderId(fallback);
    }

    private static String stripSenderId(String value) {
        if (value == null) return null;
        String cleaned = NON_ALPHANUMERIC.matcher(value).replaceAll("");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > MAX_SENDER_ID ? cleaned.substring(0, MAX_SENDER_ID) : cleaned;
    }

    /** {@code ***123}: only the last 3 characters are kept. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) return "***";
        String trimmed = phone.strip();
        return trimmed.length() <= 3 ? "***" : "***" + trimmed.substring(trimmed.length() - 3);
    }

    /** {@code j***@domain}: first character and domain kept; {@code ***} when not an address. */
    public static String maskEmail(String email) {
        if (email == null) return "***";
        String trimmed = email.strip();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) return "***";
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }

    /** A plausible address: non-blank, one {@code @} with text on both sides, no whitespace. */
    public static boolean isEmailAddress(String email) {
        if (email == null || email.isBlank()) return false;
        String trimmed = email.strip();
        int at = trimmed.indexOf('@');
        return at > 0 && at == trimmed.lastIndexOf('@') && at < trimmed.length() - 1
                && trimmed.chars().noneMatch(Character::isWhitespace);
    }
}
