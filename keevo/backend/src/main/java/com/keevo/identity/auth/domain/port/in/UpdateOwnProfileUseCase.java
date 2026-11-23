package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.UserProfileData;

import java.util.UUID;

/**
 * Story 22.19 (D17) — the authenticated user changes his own first and last name (owner included, any tenant role).
 */
public interface UpdateOwnProfileUseCase {

    /**
     * @param userId   the authenticated user
     * @param tenantId the current tenant schema
     * @return the profile as {@code GET /auth/profile} returns it
     */
    UserProfileData update(UUID userId, String tenantId, String firstName, String lastName);
}
