package com.keevo.identity.onboarding.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * TenantPreferencesJpaRepository — Spring Data JPA repository for tenant preferences.
 */
public interface TenantPreferencesJpaRepository extends JpaRepository<TenantPreferencesJpaEntity, UUID> {
    
    /**
     * Find the most recent tenant preferences (there should only be one per tenant).
     */
    Optional<TenantPreferencesJpaEntity> findTopByOrderByCreatedAtDesc();

    /** Story 19.12 — the only write of {@code default_sale_flow}: one conditional statement, the row count back. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TenantPreferencesJpaEntity p SET p.defaultSaleFlow = :flow, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.id = :id AND p.defaultSaleFlow <> :flow")
    int updateDefaultSaleFlow(@Param("id") UUID id, @Param("flow") String flow);
}
