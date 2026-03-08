package com.keevo.shared.infrastructure.persistence.jpa;

import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * AuditLogSpringRepository — Spring Data JPA repository for {@link AuditLogJpaEntity}.
 *
 * <p>Story 1.8 — Immutable audit trail.
 *
 * <p><b>Immutability enforcement by omission:</b> NO delete or update custom methods
 * are defined here. Combined with {@code @Immutable} on the entity, this makes the
 * audit log append-only at the ORM level.
 *
 * <p><b>Tenant isolation:</b> All queries are automatically scoped to the tenant
 * schema currently set in {@code TenantContext}, via
 * {@code SchemaAwareMultiTenantConnectionProvider}. There is NO {@code tenant_id}
 * column — the schema itself IS the tenant boundary.
 *
 * <p><b>Three query methods for three routing branches in AuditController:</b>
 * <ol>
 *   <li>Both filters present → {@link #findByEntityTypeAndEntityIdOrderByOccurredAtDesc}</li>
 *   <li>EntityType only      → {@link #findByEntityTypeOrderByOccurredAtDesc}</li>
 *   <li>No filters           → {@link #findAllByOrderByOccurredAtDesc} (full tenant log)</li>
 * </ol>
 */
public interface AuditLogSpringRepository extends JpaRepository<AuditLogJpaEntity, UUID> {

    /**
     * Find all audit entries for a specific entity (type + id), sorted newest-first.
     */
    List<AuditLogJpaEntity> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, UUID entityId);

    /**
     * Find all audit entries for a given entity type (all instances), sorted newest-first.
     */
    List<AuditLogJpaEntity> findByEntityTypeOrderByOccurredAtDesc(String entityType);

    /**
     * Retrieve the full audit log for the current tenant, sorted newest-first.
     * Safe: the schema routing in TenantContext limits results to the caller's tenant.
     */
    List<AuditLogJpaEntity> findAllByOrderByOccurredAtDesc();
}
