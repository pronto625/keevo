package com.keevo.shared.domain;

import java.util.regex.Pattern;

/**
 * PhoneNumbers — canonical phone-number form (Story 12.12).
 *
 * <p>Canonical form is {@code +<digits>} — what the mobile app sends ({@code +<dial><national>}).
 * Normalising at every entry point (login, registration, employee create/update, password reset,
 * rate-limiter key) makes {@code 237699…} and {@code +237699…} the same identity, so uniqueness
 * checks and lookups cannot be bypassed by a formatting variant.
 */
public final class PhoneNumbers {

    /** Dial prefix added to a 9-digit Cameroonian number typed without its country code. */
    public static final String CAMEROON = "+237";

    private static final Pattern LOCAL_9_DIGITS = Pattern.compile("^[0-9]{9}$");
    /** E.164 pattern, also used verbatim in SQL (POSIX regex) by the crm phone backfill — one definition. */
    public static final String E164_REGEX = "^\\+[1-9][0-9]{7,14}$";
    private static final Pattern E164 = Pattern.compile(E164_REGEX);

    private PhoneNumbers() {}

    /**
     * Story 20.2 — the E.164 form of a typed number, the single normalization shared by invoicing (WhatsApp number of
     * an invoice, Story 14.2) and the crm (duplicate detection): separators (spaces, {@code -}, {@code .}, {@code (},
     * {@code )}) stripped, {@code 00} → {@code +}, a 9-digit local number gets {@code defaultCountryCode}
     * ({@code 677 00 01 11} → {@code +237677000111}), any other digits get {@code +}.
     *
     * @param defaultCountryCode dial prefix with its {@code +} (e.g. {@link #CAMEROON}); {@code null} = none
     * @return the E.164 number, or {@code null} when blank or not a valid E.164 number once normalized
     */
    public static String toE164(String raw, String defaultCountryCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.strip().replaceAll("[\\s\\-.()]", "");
        String phone;
        if (cleaned.startsWith("+")) {
            phone = cleaned;
        } else if (cleaned.startsWith("00")) {
            phone = "+" + cleaned.substring(2);
        } else if (defaultCountryCode != null && LOCAL_9_DIGITS.matcher(cleaned).matches()) {
            phone = defaultCountryCode + cleaned;
        } else {
            phone = normalize(cleaned);
        }
        return E164.matcher(phone).matches() ? phone : null;
    }

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
