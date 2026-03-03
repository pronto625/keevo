package com.keevo.identity.auth.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * TenantJpaRepository — Spring Data JPA repository for public.tenants table.
 */
@Repository
public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsByCode(String code);
}
