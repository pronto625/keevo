package com.keevo.shared.domain;

/**
 * PhoneNumbers — canonical phone-number form (Story 12.12).
 *
 * <p>Canonical form is {@code +<digits>} — what the mobile app sends ({@code +<dial><national>}).
 * Normalising at every entry point (login, registration, employee create/update, password reset,
 * rate-limiter key) makes {@code 237699…} and {@code +237699…} the same identity, so uniqueness
 * checks and lookups cannot be bypassed by a formatting variant.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /**
     * Trims, drops spaces / {@code -} / {@code .} / {@code (} / {@code )} and prefixes {@code +}
     * when missing. {@code null} stays {@code null}; a blank value becomes empty (validation of
     * presence / format stays with the caller).
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[\\s\\-.()]", "");
        if (cleaned.isEmpty() || cleaned.startsWith("+")) {
            return cleaned;
        }
        return "+" + cleaned;
    }
}
