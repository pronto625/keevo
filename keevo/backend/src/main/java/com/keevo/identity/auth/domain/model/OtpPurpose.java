package com.keevo.identity.auth.domain.model;

/**
 * Story 21.7 — what a one-time code is for. Each purpose stores its challenges under its own key, so a code sent for
 * one purpose never verifies under another (a client-portal code cannot open a merchant session, and a merchant
 * sign-in code cannot open the client portal), and each purpose has its own daily failure cap.
 *
 * <p>{@link #SIGN_IN} keeps the bare identifier (the 19.2 rows, unchanged); {@link #CLIENT_PORTAL} prefixes it with
 * {@code portal:} (phones only, so the key stays far below the 254-character column).
 */
public enum OtpPurpose {

    /** Merchant sign-in (Story 19.2). */
    SIGN_IN(""),
    /** End client of the client portal (Story 21.7). */
    CLIENT_PORTAL("portal:");

    private final String prefix;

    OtpPurpose(String prefix) {
        this.prefix = prefix;
    }

    /** The {@code otp_challenges.identifier} value of a code of this purpose sent to {@code identifier}. */
    public String challengeKey(OtpIdentifier identifier) {
        return prefix + identifier.value();
    }
}
