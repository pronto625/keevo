package com.keevo.identity.onboarding.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
