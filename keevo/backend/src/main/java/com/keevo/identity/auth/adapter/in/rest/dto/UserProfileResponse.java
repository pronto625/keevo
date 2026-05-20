package com.keevo.identity.auth.adapter.in.rest.dto;

import java.util.UUID;

/**
 * UserProfileResponse — REST DTO for GET /api/v1/auth/profile (Story 8.6 AC4).
 *
 * <p>EMPLOYEE: firstName, lastName, storeId, storeName populated from employee record.
 * OWNER: firstName, lastName, storeId, storeName all null.
 */
public record UserProfileResponse(
        UUID userId,
        String phoneNumber,
        String role,
        String firstName,
        String lastName,
        String storeId,
        String storeName
) {}
