package com.keevo.identity.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to platform users ({@code public.users}) for other modules (Story 12.2d). */
public interface UserQueryPort {

    /** The user's phone number (E.164); empty if the user does not exist. Not tenant-scoped. */
    Optional<String> findPhoneNumber(UUID userId);

    /** User ids of the OWNER members of the tenant. */
    List<UUID> findOwnerUserIds(String tenantSchema);

    /**
     * Tenant schemas ({@code kv_xxxxxx}) of every membership of the user, as listed at login
     * (Story 12.2f — cross-tenant audit of password reset events). Not tenant-scoped; empty if the
     * user has no membership.
     */
    List<String> findMembershipSchemas(UUID userId);
}
