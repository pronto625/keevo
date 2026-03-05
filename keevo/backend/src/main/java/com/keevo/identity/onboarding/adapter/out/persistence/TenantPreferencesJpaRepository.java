package com.keevo.identity.onboarding.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * TenantPreferencesJpaRepository — Spring Data JPA repository for tenant preferences.
 */
public interface TenantPreferencesJpaRepository extends JpaRepository<TenantPreferencesJpaEntity, UUID> {
}
