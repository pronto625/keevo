package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * TenantSpringRepository — Spring Data JPA interface for the public.tenants table.
 *
 * <p>Infrastructure layer only. Consumed exclusively by
 * {@link com.keevo.identity.auth.adapter.out.persistence.impl.TenantRepositoryAdapter}.
 * Never injected directly into the domain or application layers.
 */
@Repository
public interface TenantSpringRepository extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsByCode(String code);

    java.util.Optional<TenantJpaEntity> findBySchemaName(String schemaName);

    /** Story 1.7 — needed by SelectTenantService to resolve tenantCode → Tenant. */
    java.util.Optional<TenantJpaEntity> findByCode(String code);
}
