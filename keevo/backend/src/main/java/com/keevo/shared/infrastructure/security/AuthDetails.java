package com.keevo.shared.infrastructure.security;

import java.util.UUID;

/**
 * AuthDetails — supplementary authentication data stored in
 * {@link org.springframework.security.core.Authentication#getDetails()}.
 *
 * <p>Replaces the previous ad-hoc {@code UUID storeId} pattern so that
 * controllers can access both the authenticated user's first name (from
 * the JWT {@code firstName} claim) and, for EMPLOYEE tokens, the assigned
 * store UUID without additional database lookups.
 *
 * <p>Story 14.10 — Employee action notifications.
 */
public record AuthDetails(
        String firstName,   // nullable — from JWT "firstName" claim
        UUID storeId        // nullable — only for EMPLOYEE tokens
) {
    /** Convenience factory for OWNER tokens (no storeId). */
    public static AuthDetails owner(String firstName) {
        return new AuthDetails(firstName, null);
    }

    /** Convenience factory for EMPLOYEE tokens. */
    public static AuthDetails employee(String firstName, UUID storeId) {
        return new AuthDetails(firstName, storeId);
    }
}
