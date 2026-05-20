package com.keevo.identity.auth.domain.model;

import java.util.UUID;

/**
 * UserProfileData — Domain model for user profile (Story 8.6 AC4).
 *
 * <p>Pure Java — no framework dependencies.
 * Produced by {@code UserProfileService} (Facade) and mapped to
 * {@code UserProfileResponse} at the REST layer.
 *
 * <p>EMPLOYEE: firstName, lastName, storeId, storeName populated.
 * OWNER: firstName, lastName, storeId, storeName all null.
 */
public record UserProfileData(
        UUID userId,
        String phoneNumber,
        String role,
        String firstName,
        String lastName,
        String storeId,
        String storeName
) {}
