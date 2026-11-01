package com.keevo.shared.infrastructure.security.access;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.11 — the server-side « access state » of a user in one account, read by
 * {@link com.keevo.shared.infrastructure.security.JwtAuthFilter} on every request (through
 * {@link AccessStateService}). The access token proves identity only; every right comes from here.
 *
 * @param role                   membership role ({@code OWNER}, {@code EMPLOYEE}, {@code SUPER_ADMIN})
 * @param membershipActive       membership {@code is_active}
 * @param tenantStatus           account status ({@code ACTIVE}, {@code SUSPENDED}, {@code DELETION_PENDING}, ...)
 * @param tokensValidAfter       revocation cutoff ({@code tokens_valid_after}), null when never revoked
 * @param employee               the employee row of the account (null when the user has none)
 * @param openSessionIds         device sessions that still hold a live refresh token
 */
public record AccessState(
        String role,
        boolean membershipActive,
        String tenantStatus,
        Instant tokensValidAfter,
        EmployeeState employee,
        Set<UUID> openSessionIds
) {

    /**
     * Employee row of the account schema.
     *
     * @param storeId                assigned store
     * @param status                 {@code ACTIVE} / {@code INACTIVE}
     * @param passwordChangeRequired forced password change pending
     * @param firstName              first name (actor label in notifications and audit)
     */
    public record EmployeeState(UUID storeId, String status, boolean passwordChangeRequired, String firstName) {
    }

    public AccessState {
        openSessionIds = openSessionIds == null ? Set.of() : Set.copyOf(openSessionIds);
    }

    public boolean isSessionOpen(UUID sessionId) {
        return openSessionIds.contains(sessionId);
    }
}
