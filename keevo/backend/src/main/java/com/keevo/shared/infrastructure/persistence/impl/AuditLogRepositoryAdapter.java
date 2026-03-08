package com.keevo.shared.infrastructure.persistence.impl;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.keevo.shared.infrastructure.persistence.jpa.AuditLogSpringRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AuditLogRepositoryAdapter — JPA adapter implementing {@link AuditPort}.
 *
 * <p>Story 1.8 — Immutable audit trail.
 *
 * <p>GoF Patterns:
 * <ul>
 *   <li><b>Strategy</b> — implements {@code AuditPort}; replaceable with a remote SIEM adapter.</li>
 *   <li><b>Façade</b>   — hides JPA boilerplate (entity construction, save, mapping) behind
 *       the clean {@code AuditPort} interface.</li>
 * </ul>
 *
 * <p><b>Tenant isolation:</b> all operations are automatically scoped to the current tenant
 * schema via {@code TenantContext} + {@code SchemaAwareMultiTenantConnectionProvider}.
 * Callers that need explicit TenantContext management (public endpoints) are responsible
 * for calling {@code TenantContext.set()} before and {@code TenantContext.clear()} after
 * (see {@code AuditEventListener} for public-endpoint handling).
 *
 * <p><b>Constructor injection</b> — NO {@code @Autowired} field injection (established
 * pattern in all adapters per architecture guide).
 */
@Component
public class AuditLogRepositoryAdapter implements AuditPort {

    private final AuditLogSpringRepository repository;

    public AuditLogRepositoryAdapter(AuditLogSpringRepository repository) {
        this.repository = repository;
    }

    // ── AuditPort: command ────────────────────────────────────────────────────

    /**
     * Persist an immutable audit entry to the tenant's {@code audit_log} table.
     *
     * <p>{@code REQUIRES_NEW} — always opens a fresh Hibernate session bound to a new
     * JDBC connection. This forces {@link com.keevo.shared.infrastructure.persistence.SchemaAwareMultiTenantConnectionProvider}
     * to call {@code getConnection(tenantId)} which executes
     * {@code SET search_path TO "kv_xxxxxx"} on the new connection.
     *
     * <p>This is required for public-endpoint callers (e.g. {@code UserRegisteredEvent}
     * from {@code /auth/register}) where the outer transaction uses a connection scoped
     * to the public schema. Without {@code REQUIRES_NEW}, {@code repository.save()} would
     * join the outer session and silently insert into {@code public.audit_log} (which does
     * not exist, causing a silent no-op or error).
     *
     * <p>Audit entries are intentionally independent of the triggering transaction:
     * if the caller rolls back, the audit entry is still persisted (audit-on-attempt semantics).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String tenantId, String action,
                       String entityType, UUID entityId,
                       String valueBefore, String valueAfter) {
        AuditLogJpaEntity entity = new AuditLogJpaEntity(
                UUID.randomUUID(),
                actorId,
                entityType,
                entityId,
                action,
                valueBefore,
                valueAfter,
                Instant.now()
        );
        repository.save(entity);
    }

    // ── AuditPort: queries ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryRecord> findByEntityTypeAndEntityId(String entityType, UUID entityId) {
        return repository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId)
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryRecord> findByEntityType(String entityType) {
        return repository
                .findByEntityTypeOrderByOccurredAtDesc(entityType)
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryRecord> findAll() {
        return repository
                .findAllByOrderByOccurredAtDesc()
                .stream()
                .map(this::toRecord)
                .toList();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private AuditEntryRecord toRecord(AuditLogJpaEntity e) {
        return new AuditEntryRecord(
                e.getId(),
                e.getEntityType(),
                e.getEntityId(),
                e.getAction(),
                e.getValueBefore(),
                e.getValueAfter(),
                e.getUserId(),
                e.getOccurredAt()
        );
    }
}
