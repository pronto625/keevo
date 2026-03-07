package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.UserTenantMembershipJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserTenantMembershipSpringRepository — Spring Data JPA repository for user-tenant memberships.
 *
 * <p>Story 1.7 — Multi-tenant user memberships.
 * Targets the {@code public.user_tenant_memberships} table.
 */
public interface UserTenantMembershipSpringRepository
        extends JpaRepository<UserTenantMembershipJpaEntity, UUID> {

    /** Find all active memberships for a given user. */
    List<UserTenantMembershipJpaEntity> findByUserIdAndActiveTrue(UUID userId);

    /** Find all memberships (active or not) for a given user. */
    List<UserTenantMembershipJpaEntity> findByUserId(UUID userId);

    /** Find a specific membership by (userId, tenantId). */
    Optional<UserTenantMembershipJpaEntity> findByUserIdAndTenantId(UUID userId, UUID tenantId);
}
