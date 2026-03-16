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
     * Deactivate all memberships for a user (set is_active = false).
     * Story 3.5 — employee deactivation.
     */
    void deactivateByUserId(UUID userId);

    /**
     * Reactivate all memberships for a user (set is_active = true).
     * Story 3.5 — employee reactivation.
     */
    void reactivateByUserId(UUID userId);
}
