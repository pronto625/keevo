package com.keevo.identity.auth.adapter.out.persistence.jpa;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** v1s-15-9 — tenants in {@code status} whose deletion date is at or before {@code now}. */
    java.util.List<TenantJpaEntity> findByStatusAndDeletionScheduledAtLessThanEqual(
            String status, java.time.Instant now);

    /**
     * v1s-15-9 — owner cancel as one conditional statement. Matches only a tenant that is still
     * DELETION_PENDING, inside its grace period (DB clock) and whose schema still exists. A
     * concurrent scheduler holding the row lock makes this UPDATE wait, then re-evaluate against
     * the committed DELETED row and match nothing.
     *
     * @return the number of updated rows (0 or 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL, updated_at = now() "
            + "WHERE schema_name = :schemaName AND status = 'DELETION_PENDING' "
            + "AND deletion_scheduled_at > now() "
            + "AND to_regnamespace(schema_name) IS NOT NULL",
            nativeQuery = true)
    int cancelPendingDeletion(@Param("schemaName") String schemaName);
}
