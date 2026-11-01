package com.keevo.identity.auth.adapter.in.rest.dto;

import java.util.UUID;

/**
 * UserProfileResponse — REST DTO for GET /api/v1/auth/profile (Story 8.6 AC4, Story 16.11).
 *
 * <p>Story 16.11 — the source of role, store, first name, password-change flag, account status and
 * onboarding state for the apps (cached on the device for offline display only).
 */
public record UserProfileResponse(
        UUID userId,
        String phoneNumber,
        String role,
        String firstName,
        String lastName,
        String storeId,
        String storeName,
        boolean passwordChangeRequired,
        String tenantStatus,
        boolean onboardingCompleted
) {}
