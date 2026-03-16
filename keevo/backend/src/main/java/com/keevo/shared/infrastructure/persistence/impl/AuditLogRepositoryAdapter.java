package com.keevo.shared.infrastructure.persistence.impl;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.keevo.shared.infrastructure.persistence.jpa.AuditLogSpringRepository;
import jakarta.persistence.EntityManager;
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

    private static final String SELECT_WITH_PHONE = """
            SELECT a.id::text, a.entity_type, a.entity_id::text, a.action,
                   a.value_before, a.value_after, a.user_id::text, a.occurred_at,
                   u.phone_number
            FROM audit_log a
            LEFT JOIN public.users u ON a.user_id = u.id
            """;

    private final AuditLogSpringRepository repository;
    private final EntityManager            entityManager;

    public AuditLogRepositoryAdapter(AuditLogSpringRepository repository,
                                     EntityManager entityManager) {
        this.repository    = repository;
        this.entityManager = entityManager;
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

    // ── AuditPort: queries (native SQL — LEFT JOIN public.users for actorPhone) ──

    @Override
    @Transactional(readOnly = true)
    public AuditPage findByEntityTypeAndEntityId(String entityType, UUID entityId, int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                SELECT_WITH_PHONE +
                "WHERE a.entity_type = :entityType AND a.entity_id = :entityId::uuid " +
                "ORDER BY a.occurred_at DESC")
                .setParameter("entityType", entityType)
                .setParameter("entityId", entityId.toString())
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();
        return toPage(rows, size);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPage findByEntityType(String entityType, int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                SELECT_WITH_PHONE +
                "WHERE a.entity_type = :entityType " +
                "ORDER BY a.occurred_at DESC")
                .setParameter("entityType", entityType)
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();
        return toPage(rows, size);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditPage findAll(int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                SELECT_WITH_PHONE + "ORDER BY a.occurred_at DESC")
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();
        return toPage(rows, size);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private AuditPage toPage(List<Object[]> rows, int size) {
        boolean hasMore = rows.size() > size;
        return new AuditPage(mapRows(hasMore ? rows.subList(0, size) : rows), hasMore);
    }

    private List<AuditEntryRecord> mapRows(List<Object[]> rows) {
        return rows.stream().map(r -> new AuditEntryRecord(
                UUID.fromString((String) r[0]),   // id
                (String) r[1],                    // entityType
                UUID.fromString((String) r[2]),   // entityId
                (String) r[3],                    // action
                (String) r[4],                    // valueBefore (nullable)
                (String) r[5],                    // valueAfter (nullable)
                UUID.fromString((String) r[6]),   // userId
                (String) r[8],                    // actorPhone (nullable — LEFT JOIN)
                toInstant(r[7])                   // occurredAt
        )).toList();
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i)                       return i;
        if (o instanceof java.sql.Timestamp ts)           return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt)    return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt)     return ldt.toInstant(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("Cannot convert " + o.getClass() + " to Instant");
    }
}
