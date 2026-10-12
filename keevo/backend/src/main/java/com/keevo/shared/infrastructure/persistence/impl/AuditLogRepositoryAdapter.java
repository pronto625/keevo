package com.keevo.shared.infrastructure.persistence.impl;

import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.entity.AuditLogJpaEntity;
import com.keevo.shared.infrastructure.persistence.jpa.AuditLogSpringRepository;
import com.keevo.shared.infrastructure.security.CurrentActor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Story v1s-18-6 — enriched read projection. {@code public.users} is the only cross-schema
     * join; {@code stores}/{@code employees} resolve to the tenant schema via search_path.
     * The employee lookup is LATERAL + LIMIT 1 so a user with several employee rows never
     * duplicates an audit row. Story v1s-18-7 — {@code te} gives Employee targets without a
     * stored label (legacy rows, events carrying no name) the employee's current name.
     */
    /**
     * Displayed object label: stored label, else the target employee's name (v1s-18-7).
     * Must stay identical to the label column of {@link #SELECT_ENRICHED}.
     */
    private static final String LABEL_EXPR =
            "COALESCE(a.entity_label, NULLIF(TRIM(CONCAT_WS(' ', te.first_name, te.last_name)), ''))";

    private static final String SELECT_ENRICHED = """
            SELECT CAST(a.id AS text), a.entity_type, CAST(a.entity_id AS text), a.action,
                   a.value_before, a.value_after, CAST(a.user_id AS text), a.occurred_at,
                   u.phone_number,
                   CAST(a.store_id AS text), st.name,
                   COALESCE(a.entity_label,
                            NULLIF(TRIM(CONCAT_WS(' ', te.first_name, te.last_name)), '')),
                   a.device_id, a.source,
                   CAST(a.metadata AS text),
                   NULLIF(TRIM(COALESCE(e.first_name, '') || ' ' || COALESCE(e.last_name, '')), ''),
                   a.metadata ->> 'actorLabel',
                   u.role
            FROM audit_log a
            LEFT JOIN public.users u ON a.user_id = u.id
            LEFT JOIN stores st ON st.id = a.store_id
            LEFT JOIN employees te ON a.entity_type = 'Employee' AND te.id = a.entity_id
            LEFT JOIN LATERAL (
                SELECT emp.first_name, emp.last_name FROM employees emp
                WHERE emp.user_id = a.user_id
                ORDER BY emp.updated_at DESC
                LIMIT 1
            ) e ON TRUE
            """;

    private static final String ORDER_BY = " ORDER BY a.occurred_at DESC, a.id DESC";

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
     * join the outer session and silently insert into {@code public.audit_log}.
     *
     * <p>Audit entries are intentionally independent of the triggering transaction:
     * if the caller rolls back, the audit entry is still persisted (audit-on-attempt semantics).
     *
     * <p>{@code occurred_at} is truncated to microseconds (PostgreSQL precision) so the keyset
     * cursor built from a read row always matches the stored value exactly.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry e) {
        AuditLogJpaEntity entity = new AuditLogJpaEntity(
                UUID.randomUUID(),
                e.actorId(),
                e.entityType(),
                e.entityId(),
                e.action(),
                e.valueBefore(),
                e.valueAfter(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                e.storeId(),
                e.entityLabel(),
                e.deviceId(),
                e.source(),
                e.metadataJson()
        );
        repository.save(entity);
    }

    // ── AuditPort: queries ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AuditSlice search(AuditQuery q, String cursor, Integer page, int size) {
        AuditQuery query = q != null ? q : AuditQuery.none();
        AuditCursor after = cursor != null ? AuditCursor.decode(cursor) : null;

        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (query.from() != null) {
            where.add("a.occurred_at >= :fromTs");
            params.put("fromTs", query.from());
        }
        if (query.to() != null) {
            where.add("a.occurred_at <= :toTs");
            params.put("toTs", query.to());
        }
        if (query.storeId() != null) {
            where.add("a.store_id = :storeId");
            params.put("storeId", query.storeId());
        }
        if (query.userId() != null) {
            where.add("a.user_id = :userId");
            params.put("userId", query.userId());
        }
        if (query.action() != null) {
            where.add("a.action = :action");
            params.put("action", query.action());
        }
        if (query.entityType() != null) {
            where.add("a.entity_type = :entityType");
            params.put("entityType", query.entityType());
        }
        if (query.entityId() != null) {
            where.add("a.entity_id = :entityId");
            params.put("entityId", query.entityId());
        }
        if (query.q() != null && !query.q().isBlank()) {
            // Same expression as the displayed label (employee-name fallback included).
            where.add(LABEL_EXPR + " ILIKE :q ESCAPE '\\'");
            params.put("q", "%" + escapeLike(query.q().trim()) + "%");
        }
        if (after != null) {
            where.add("(a.occurred_at, a.id) < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS uuid))");
            params.put("cursorAt", after.occurredAt());
            params.put("cursorId", after.id());
        }

        StringBuilder sql = new StringBuilder(SELECT_ENRICHED);
        if (!where.isEmpty()) {
            sql.append("WHERE ").append(String.join(" AND ", where));
        }
        sql.append(ORDER_BY);

        Query nq = entityManager.createNativeQuery(sql.toString());
        params.forEach(nq::setParameter);
        if (after == null) {
            int p = page != null ? Math.max(page, 0) : 0;
            nq.setFirstResult(p * size);
        }
        nq.setMaxResults(size + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = nq.getResultList();
        boolean hasMore = rows.size() > size;
        List<AuditEntryRecord> entries = mapRows(hasMore ? rows.subList(0, size) : rows);
        String nextCursor = null;
        if (hasMore && !entries.isEmpty()) {
            AuditEntryRecord last = entries.get(entries.size() - 1);
            nextCursor = new AuditCursor(last.occurredAt(), last.id()).encode();
        }
        return new AuditSlice(entries, hasMore, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditEntryRecord> findById(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(SELECT_ENRICHED + "WHERE a.id = :id")
                .setParameter("id", id)
                .setMaxResults(1)
                .getResultList();
        return mapRows(rows).stream().findFirst();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

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
                toInstant(r[7]),                  // occurredAt
                r[9] != null ? UUID.fromString((String) r[9]) : null, // storeId
                (String) r[10],                   // storeName (LEFT JOIN stores)
                (String) r[11],                   // entityLabel
                (String) r[12],                   // deviceId
                (String) r[13],                   // source
                (String) r[14],                   // metadata (raw JSON text)
                actorName((String) r[15], (String) r[16], (String) r[17])
        )).toList();
    }

    /** Employee name &gt; label stored at write time &gt; role-based fallback. Never a UUID. */
    static String actorName(String employeeName, String storedLabel, String role) {
        if (employeeName != null && !employeeName.isBlank()) return employeeName;
        if (storedLabel != null && !storedLabel.isBlank()) return storedLabel;
        return CurrentActor.fallbackLabel(role);
    }

    /** Escapes LIKE wildcards so {@code %} and {@code _} in the search text match literally. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i)                       return i;
        if (o instanceof java.sql.Timestamp ts)           return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt)    return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt)     return ldt.toInstant(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("Cannot convert " + o.getClass() + " to Instant");
    }
}
