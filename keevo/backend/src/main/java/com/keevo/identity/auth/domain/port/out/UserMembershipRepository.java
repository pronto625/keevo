package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.UserTenantMembership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserMembershipRepository — Driven port for user-tenant membership persistence.
 *
 * <p>Story 1.7 — Multi-Tenant User Memberships.
 * Operations target the {@code public.user_tenant_memberships} table.
 */
public interface UserMembershipRepository {

    /**
     * Persist a new membership. If a duplicate (userId, tenantId) already exists,
     * the adapter must throw {@code DomainException(MEMBERSHIP_ALREADY_EXISTS)}.
     */
    UserTenantMembership save(UserTenantMembership membership);

    /** Find all active memberships for a given user. */
    List<UserTenantMembership> findByUserId(UUID userId);

    /** Find a specific membership entry for (userId, tenantId). */
    Optional<UserTenantMembership> findByUserIdAndTenantId(UUID userId, UUID tenantId);

    /**
     * Deactivate the user's membership in ONE tenant (set is_active = false); no-op when the
     * user has no membership there. Story 3.5 — employee deactivation; scoped to the tenant by
     * the v1s-13-9 follow-up review (the former all-tenant variant also cut the user's access to
     * every other shop, including one they own).
     */
    void deactivate(UUID userId, UUID tenantId);

    /** Reactivate the user's membership in ONE tenant (set is_active = true). Story 3.5. */
    void reactivate(UUID userId, UUID tenantId);

    /**
     * Story 14.11 — update the role for a specific (userId, tenantId) membership.
     */
    void updateRole(UUID userId, UUID tenantId, String newRole);
}
