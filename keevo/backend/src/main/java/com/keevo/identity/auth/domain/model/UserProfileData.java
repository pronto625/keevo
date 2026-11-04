package com.keevo.identity.auth.domain.model;

import java.util.UUID;

/**
 * UserProfileData — Domain model for user profile (Story 8.6 AC4, extended by Story 16.11).
 *
 * <p>Pure Java — no framework dependencies. Story 16.11: the apps read role, store, first name,
 * the password-change flag, the account status and the onboarding state from here (the access
 * token proves identity only). Display only — the server decides on every request.
 *
 * <p>EMPLOYEE: firstName, lastName, storeId, storeName populated.
 * OWNER / SUPER_ADMIN: firstName, lastName, storeId, storeName null (unless the owner also has an
 * employee row, then its names).
 *
 * <p>Story 19.4 — {@code username}: the sign-in username of the account ({@code null} when none, or when it is the
 * technical username of a social account, never shown).
 */
public record UserProfileData(
        UUID userId,
        String phoneNumber,
        String role,
        String firstName,
        String lastName,
        String storeId,
        String storeName,
        boolean passwordChangeRequired,
        String tenantStatus,
        boolean onboardingCompleted,
        String username
) {
    /** Pre-16.11 shape (tests). */
    public UserProfileData(UUID userId, String phoneNumber, String role, String firstName,
                           String lastName, String storeId, String storeName) {
        this(userId, phoneNumber, role, firstName, lastName, storeId, storeName, false, "ACTIVE", true);
    }

    /** Pre-19.4 shape (no username). */
    public UserProfileData(UUID userId, String phoneNumber, String role, String firstName, String lastName,
                           String storeId, String storeName, boolean passwordChangeRequired, String tenantStatus,
                           boolean onboardingCompleted) {
        this(userId, phoneNumber, role, firstName, lastName, storeId, storeName, passwordChangeRequired, tenantStatus,
             onboardingCompleted, null);
    }
}
