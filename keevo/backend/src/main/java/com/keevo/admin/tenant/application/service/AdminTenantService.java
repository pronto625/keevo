package com.keevo.admin.tenant.application.service;

import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.admin.tenant.domain.model.*;
import com.keevo.admin.tenant.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AdminTenantService — implements Super Admin tenant management use cases.
 *
 * <p><b>Architecture rules:</b>
 * <ol>
 *   <li>listTenants(): queries public schema via JdbcTemplate ONLY — no TenantContext manipulation.
 *       Cross-schema per-tenant metrics (storeCount, lastActivityAt) use parameterized schema names
 *       from {@code public.tenants.schema_name} (validated at provisioning and re-checked here with {@code TenantSchema}: pattern {@code ^kv_[a-z0-9]{6}$}).
 *   </li>
 *   <li>getTenantDetail(): switches TenantContext to the target tenant's schema for stores,
 *       employees, and audit log, then restores it — same pattern as ActivatePlanService.
 *   </li>
 *   <li>Always excludes SUPER_ADMIN system tenant (id = 00000000-0000-0000-0000-000000000000).
 *   </li>
 * </ol>
 *
 * <p><b>Schema name safety:</b> schemaName is sourced from {@code public.tenants.schema_name} and
 * was validated at provisioning time to match {@code ^kv_[a-z0-9]{6}$}. It is never taken from
 * user input, and every interpolation is still gated by {@link TenantSchema} (invalid rows are
 * skipped in the list, rejected in the detail) — Story v1s-13-12.
 */
@Service
public class AdminTenantService implements ListTenantsUseCase, GetTenantDetailUseCase {

    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final int AUDIT_PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final AccountDeletionScheduler accountDeletionScheduler;

    public AdminTenantService(JdbcTemplate jdbcTemplate, AccountDeletionScheduler accountDeletionScheduler) {
        this.jdbc = jdbcTemplate;
        this.accountDeletionScheduler = accountDeletionScheduler;
    }

    // ── ListTenantsUseCase ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AdminTenantListItem> execute(ListTenantsQuery query) {
        List<Object> params = new ArrayList<>();
        String whereClause = buildWhereClause(query, params);

        // Count query
        String countSql = "SELECT COUNT(*) FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner "
                + "    ON owner.id = m_owner.user_id "
                + whereClause;

        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        if (total == null || total == 0) {
            return new PageImpl<>(List.of(), PageRequest.of(query.page(), query.pageSize()), 0L);
        }

        // List query — includes employee_count via subquery
        String listSql = "SELECT t.id, t.code, t.name, t.schema_name, t.plan_type, t.status, "
                + "t.created_at, t.deletion_scheduled_at, "
                + "COALESCE(owner.phone_number, '') AS owner_phone, "
                + "(SELECT COUNT(DISTINCT m.user_id) "
                + " FROM public.user_tenant_memberships m "
                + " WHERE m.tenant_id = t.id AND m.role IN ('OWNER','EMPLOYEE')) AS employee_count "
                + "FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner "
                + "    ON owner.id = m_owner.user_id "
                + whereClause
                + " GROUP BY t.id, owner.phone_number "
                + "ORDER BY t.created_at DESC "
                + "LIMIT ? OFFSET ?";

        List<Object> listParams = new ArrayList<>(params);
        listParams.add(query.pageSize());
        listParams.add((long) query.page() * query.pageSize());

        List<AdminTenantListItem> items = jdbc.query(listSql, new TenantListRowMapper(), listParams.toArray());

        // Enrich each item with cross-schema metrics
        List<AdminTenantListItem> enriched = items.stream()
                .map(this::enrichWithCrossSchemaMetrics)
                .toList();

        return new PageImpl<>(enriched, PageRequest.of(query.page(), query.pageSize()), total);
    }

    // ── GetTenantDetailUseCase ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AdminTenantDetail execute(GetTenantDetailQuery query) {
        // Load summary from public schema
        String summarySql = "SELECT t.id, t.code, t.name, t.schema_name, t.plan_type, t.status, "
                + "t.created_at, t.deletion_scheduled_at, "
                + "COALESCE(owner.phone_number, '') AS owner_phone, "
                + "(SELECT COUNT(DISTINCT m.user_id) "
                + " FROM public.user_tenant_memberships m "
                + " WHERE m.tenant_id = t.id AND m.role IN ('OWNER','EMPLOYEE')) AS employee_count "
                + "FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner ON owner.id = m_owner.user_id "
                + "WHERE t.id = ? "
                + "GROUP BY t.id, owner.phone_number";

        List<AdminTenantListItem> summaryRows = jdbc.query(
                summarySql, new TenantListRowMapper(), query.tenantId());

        if (summaryRows.isEmpty()) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found: " + query.tenantId());
        }
        AdminTenantListItem summary = enrichWithCrossSchemaMetrics(summaryRows.get(0));
        String schemaName = loadSchemaName(query.tenantId());

        // Switch TenantContext to load cross-schema data
        String previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schemaName);
        try {
            List<AdminStoreInfo> stores = loadStores(schemaName);
            List<AdminEmployeeInfo> employees = loadEmployees(schemaName, query.tenantId());
            long auditTotal = countAuditEvents(schemaName);
            List<AdminAuditEvent> auditEvents = loadAuditEvents(schemaName, query.auditPage());
            return new AdminTenantDetail(summary, stores, employees, auditEvents, auditTotal);
        } finally {
            TenantContext.setCurrentTenant(previousTenant);
        }
    }

    // ── Cancel / Force-delete (called directly by controller) ─────────────────

    /**
     * SUPER_ADMIN cancel. Conditional UPDATE: refuses a tenant that is no longer DELETION_PENDING
     * (e.g. DELETED by a concurrent scheduler run — the UPDATE waits on its row lock, then matches
     * nothing) or whose schema is already dropped (v1s-15-9). Unlike the owner, the admin may
     * still rescue a tenant whose grace period has expired.
     */
    @Transactional
    public void cancelDeletion(UUID tenantId) {
        int updated = jdbc.update(
                "UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL "
                + "WHERE id = ? AND status = 'DELETION_PENDING' "
                + "AND to_regnamespace(schema_name) IS NOT NULL",
                tenantId);
        if (updated == 0) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found, not DELETION_PENDING or schema already dropped: " + tenantId);
        }
    }

    /**
     * SUPER_ADMIN force-delete through the scheduler's deletion routine (v1s-15-9) — previously it
     * only flipped the status to DELETED, leaking the schema, memberships and tokens.
     *
     * <p>Deliberately NOT {@code @Transactional}: the first UPDATE auto-commits a "deleting" marker
     * (DELETION_PENDING with {@code deletion_scheduled_at = now()}) before the DROP, which runs on
     * its own connection. If the routine then fails, the expired DELETION_PENDING row is retried by
     * the next daily run, and the owner cannot cancel it (grace period over).
     *
     * @throws DomainException TENANT_NOT_FOUND for an unknown, already DELETED or system tenant;
     *                         DELETION_NOT_PENDING if the tenant was cancelled concurrently
     *                         (a concurrent deletion by the daily run counts as success)
     */
    public void forceDelete(UUID tenantId) {
        int marked = jdbc.update(
                "UPDATE public.tenants SET status = 'DELETION_PENDING', deletion_scheduled_at = now() "
                + "WHERE id = ? AND id <> '" + ADMIN_TENANT_ID + "'::uuid AND status <> 'DELETED'",
                tenantId);
        if (marked == 0) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant not found or already deleted: " + tenantId);
        }
        if (!accountDeletionScheduler.deleteNow(tenantId)) {
            // A concurrent daily run may have deleted it between the marker and the routine:
            // the admin's intent is fulfilled, so that is a success, not a conflict.
            String status = jdbc.queryForObject(
                    "SELECT status FROM public.tenants WHERE id = ?", String.class, tenantId);
            if (!"DELETED".equals(status)) {
                throw new DomainException(ErrorCode.DELETION_NOT_PENDING,
                        "Tenant deletion was cancelled concurrently: " + tenantId);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildWhereClause(ListTenantsQuery q, List<Object> params) {
        StringBuilder sb = new StringBuilder("WHERE t.id <> '")
                .append(ADMIN_TENANT_ID).append("'::uuid");

        if (q.search() != null && !q.search().isBlank()) {
            // Story 12.12 — phones are stored as '+<digits>' (V10): match the raw term and its
            // normalised form so '237…' still finds '+237…'.
            sb.append(" AND (t.name ILIKE ? OR owner.phone_number IN (?, ?))");
            String like = "%" + q.search().trim() + "%";
            params.add(like);
            params.add(q.search().trim());
            params.add(PhoneNumbers.normalize(q.search()));
        }

        if (q.plan() != null && !q.plan().isBlank() && !q.plan().equals("ALL")) {
            if ("PAID".equals(q.plan())) {
                sb.append(" AND t.plan_type IN ('PREMIUM', 'PREMIUM_TRIAL')");
            } else {
                sb.append(" AND t.plan_type = 'FREE'");
            }
        }

        if (q.status() != null && !q.status().isBlank() && !q.status().equals("ALL")) {
            sb.append(" AND t.status = ?");
            params.add(q.status());
        }

        if (q.registeredFrom() != null) {
            sb.append(" AND t.created_at >= ?");
            params.add(Timestamp.from(q.registeredFrom()));
        }
        if (q.registeredTo() != null) {
            sb.append(" AND t.created_at <= ?");
            params.add(Timestamp.from(q.registeredTo()));
        }

        return sb.toString();
    }

    private AdminTenantListItem enrichWithCrossSchemaMetrics(AdminTenantListItem item) {
        // schemaName must be obtained from the DB item — we carry it via a temp holder approach:
        // The RowMapper loads schemaName into a holder via a thread-local string. Since records
        // are immutable, we pass schemaName as a column in the SQL result and strip it here.
        // See TenantListRowMapper which reads schema_name into the record's "name" position.
        // We call loadSchemaName separately to avoid complex record reshaping.
        String schemaName = safeLoadSchemaName(item.id());
        if (!TenantSchema.isValid(schemaName)) return item; // gate before SQL interpolation

        int storeCount = safeCount("SELECT COUNT(*) FROM " + schemaName + ".stores WHERE is_active = true");
        Instant lastActivity = safeMaxInstant("SELECT MAX(occurred_at) FROM " + schemaName + ".audit_log");

        return new AdminTenantListItem(
                item.id(), item.code(), item.name(), item.ownerPhone(),
                item.plan(), item.status(),
                item.registeredAt(), lastActivity,
                storeCount, item.employeeCount(),
                item.deletionScheduledAt()
        );
    }

    private String safeLoadSchemaName(UUID tenantId) {
        try {
            return jdbc.queryForObject(
                    "SELECT schema_name FROM public.tenants WHERE id = ?",
                    String.class, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private String loadSchemaName(UUID tenantId) {
        String s = jdbc.queryForObject(
                "SELECT schema_name FROM public.tenants WHERE id = ?",
                String.class, tenantId);
        if (s == null) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant schema not found: " + tenantId);
        }
        return TenantSchema.validate(s); // gate before SQL interpolation
    }

    private int safeCount(String sql) {
        try {
            Integer count = jdbc.queryForObject(sql, Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Instant safeMaxInstant(String sql) {
        try {
            Timestamp ts = jdbc.queryForObject(sql, Timestamp.class);
            return ts != null ? ts.toInstant() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<AdminStoreInfo> loadStores(String schema) {
        try {
            return jdbc.query(
                    "SELECT id::text, name, type AS store_type, 0 AS total_revenue FROM " + schema + ".stores WHERE is_active = true ORDER BY name",
                    (rs, row) -> new AdminStoreInfo(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("store_type"),
                            rs.getLong("total_revenue")
                    ));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<AdminEmployeeInfo> loadEmployees(String schema, UUID tenantId) {
        try {
            return jdbc.query(
                    "SELECT u.id::text, u.phone_number AS full_name, "
                    + "m.role, NULL AS last_login_at "
                    + "FROM public.user_tenant_memberships m "
                    + "JOIN public.users u ON u.id = m.user_id "
                    + "WHERE m.tenant_id = ? AND m.role IN ('OWNER','EMPLOYEE') "
                    + "ORDER BY m.role, u.phone_number",
                    (rs, row) -> new AdminEmployeeInfo(
                            rs.getString("id"),
                            rs.getString("full_name"),
                            rs.getString("role"),
                            null
                    ),
                    tenantId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private long countAuditEvents(String schema) {
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".audit_log "
                    + "WHERE occurred_at >= NOW() - INTERVAL '30 days'",
                    Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<AdminAuditEvent> loadAuditEvents(String schema, int page) {
        try {
            return jdbc.query(
                    "SELECT id::text, action AS event_type, user_id::text AS actor_id, occurred_at, "
                    + "entity_type || ' #' || entity_id::text AS details "
                    + "FROM " + schema + ".audit_log "
                    + "WHERE occurred_at >= NOW() - INTERVAL '30 days' "
                    + "ORDER BY occurred_at DESC "
                    + "LIMIT ? OFFSET ?",
                    (rs, row) -> new AdminAuditEvent(
                            rs.getString("id"),
                            rs.getString("event_type"),
                            rs.getString("actor_id"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("details")
                    ),
                    AUDIT_PAGE_SIZE, (long) page * AUDIT_PAGE_SIZE);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    /**
     * Maps a tenant row from the public schema.
     * Note: storeCount is set to 0 here; enrichWithCrossSchemaMetrics fills it in.
     * Note: lastActivityAt is set to null here; enrichWithCrossSchemaMetrics fills it in.
     */
    private static class TenantListRowMapper implements RowMapper<AdminTenantListItem> {
        @Override
        public AdminTenantListItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            String planType = rs.getString("plan_type");
            String plan = ("PREMIUM".equals(planType) || "PREMIUM_TRIAL".equals(planType))
                    ? "PAID" : "FREE";

            Timestamp deletionTs = rs.getTimestamp("deletion_scheduled_at");
            Timestamp createdTs  = rs.getTimestamp("created_at");

            return new AdminTenantListItem(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("owner_phone"),
                    plan,
                    rs.getString("status"),
                    createdTs  != null ? createdTs.toInstant()  : null,
                    null,    // lastActivityAt filled by enrichWithCrossSchemaMetrics
                    0,       // storeCount filled by enrichWithCrossSchemaMetrics
                    rs.getInt("employee_count"),
                    deletionTs != null ? deletionTs.toInstant() : null
            );
        }
    }
}
