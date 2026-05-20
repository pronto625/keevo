package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.UserProfileData;

import java.util.UUID;

/**
 * GetUserProfileUseCase — driving port for user profile retrieval (Story 8.6 AC3, AC4).
 *
 * <p>Facade pattern: hides the complexity of loading data from UserRepository,
 * EmployeeRepository, and StoreRepository behind a single method.
 */
public interface GetUserProfileUseCase {

    /**
     * Load the full profile for the given user within the given tenant.
     *
     * @param userId   the authenticated user's UUID
     * @param tenantId the current tenant schema name (for employee/store lookup)
     * @return populated {@link UserProfileData}
     */
    UserProfileData execute(UUID userId, String tenantId);
}
