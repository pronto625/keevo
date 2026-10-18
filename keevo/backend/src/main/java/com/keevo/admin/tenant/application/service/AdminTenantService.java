package com.keevo.admin.tenant.application.service;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.TenantLastActivitySql;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.admin.tenant.domain.model.*;
import com.keevo.admin.tenant.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AdminTenantService — implements Super Admin tenant management use cases.
 *
 * <p><b>Architecture rules:</b>
 * <ol>
 *   <li>listTenants(): queries public schema via JdbcTemplate ONLY — no TenantContext manipulation.
 *       Cross-schema per-tenant metrics (storeCount, lastActivityAt) use schema names preloaded
 *       for the whole page in one batched query on {@code public.tenants.schema_name} (validated at provisioning and re-checked here with {@code TenantSchema}: pattern {@code ^kv_[a-z0-9]{6}$}).
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

    private static final Logger log = LoggerFactory.getLogger(AdminTenantService.class);

    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final int AUDIT_PAGE_SIZE = 50;

    /** Accepted values of the list {@code plan} filter ({@code PAID} = legacy PREMIUM_TRIAL + PREMIUM). */
    private static final Set<String> PLAN_FILTERS = Set.of("ALL", "FREE", "PREMIUM_TRIAL", "PREMIUM", "PAID");

    /** Trimmed « first last » of an employees row aliased {@code e}; NULL when both are blank. */
    private static final String EMPLOYEE_FULL_NAME =
            "NULLIF(TRIM(CONCAT_WS(' ', TRIM(e.first_name), TRIM(e.last_name))), '')";

    private final JdbcTemplate jdbc;
    private final AccountDeletionScheduler accountDeletionScheduler;

    public AdminTenantService(JdbcTemplate jdbcTemplate, AccountDeletionScheduler accountDeletionScheduler) {
        this.jdbc = jdbcTemplate;
        this.accountDeletionScheduler = accountDeletionScheduler;
    }

    // ── ListTenantsUseCase ────────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
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

        long offset = (long) query.page() * query.pageSize();
        Long total;
        if (query.lastActivityFrom() != null || query.lastActivityTo() != null) {
            // 9-1 AC3 — last activity = latest merchant event (TenantLastActivitySql, same definition
            // as the column). It lives in each tenant schema, so the candidates are pre-filtered per schema (one
            // statement each, acceptable at Phase 1 volumes), then paginated on their ids.
            List<UUID> active = tenantsActiveInRange(whereClause, params,
                    query.lastActivityFrom(), query.lastActivityTo());
            total = (long) active.size();
            List<UUID> pageIds = active.stream().skip(offset).limit(query.pageSize()).toList();
            if (pageIds.isEmpty()) {
                return new PageImpl<>(List.of(), PageRequest.of(query.page(), query.pageSize()), total);
            }
            whereClause = whereClause + " AND t.id IN ("
                    + pageIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ")";
            params.addAll(pageIds);
            offset = 0;
        } else {
            total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        }
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
        listParams.add(offset);

        List<AdminTenantListItem> items = jdbc.query(listSql, new TenantListRowMapper(), listParams.toArray());

        // Story 11.5 — one batched schema_name lookup for the whole page (was one per row)
        Map<UUID, String> schemaByTenant = loadSchemaNames(
                items.stream().map(AdminTenantListItem::id).toList());

        // Enrich each item with cross-schema metrics
        List<AdminTenantListItem> enriched = items.stream()
                .map(it -> enrichWithCrossSchemaMetrics(it, schemaByTenant.get(it.id())))
                .toList();

        return new PageImpl<>(enriched, PageRequest.of(query.page(), query.pageSize()), total);
    }

    // ── GetTenantDetailUseCase ────────────────────────────────────────────────

    @Override
    // Deliberately NOT @Transactional: each per-tenant statement autocommits, so one broken tenant
    // schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
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
        // Story 11.5 — schema_name loaded once and reused for the enrichment
        String schemaName = loadSchemaName(query.tenantId());
        AdminTenantListItem summary = enrichWithCrossSchemaMetrics(summaryRows.get(0), schemaName);

        // Switch TenantContext to load cross-schema data
        String previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schemaName);
        try {
            List<AdminStoreInfo> stores = loadStores(schemaName, query.tenantId());
            List<AdminEmployeeInfo> employees = loadEmployees(schemaName, query.tenantId());
            long auditTotal = countAuditEvents(schemaName, query.tenantId());
            List<AdminAuditEvent> auditEvents = loadAuditEvents(schemaName, query.tenantId(), query.auditPage());
            AdminPlanInfo plan = loadPlan(schemaName, query.tenantId());
            AdminSuspensionInfo suspension = loadSuspension(query.tenantId());
            return new AdminTenantDetail(summary, stores, employees, auditEvents, auditTotal, plan, suspension);
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

        String plan = normalizePlanFilter(q.plan());
        if (plan != null) {
            if ("PAID".equals(plan)) {
                sb.append(" AND t.plan_type IN ('PREMIUM', 'PREMIUM_TRIAL')");
            } else {
                sb.append(" AND t.plan_type = ?");
                params.add(plan);
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

    /**
     * Ids (newest tenant first) of the tenants matching the public filters whose last activity —
     * the latest merchant event, see {@link TenantLastActivitySql} — falls in [{@code from}, {@code to}]. A
     * tenant never active, with an invalid schema name or an unreadable schema is excluded.
     */
    private List<UUID> tenantsActiveInRange(String whereClause, List<Object> params, Instant from, Instant to) {
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT t.id, t.schema_name FROM public.tenants t "
                + "LEFT JOIN public.user_tenant_memberships m_owner "
                + "    ON m_owner.tenant_id = t.id AND m_owner.role = 'OWNER' "
                + "LEFT JOIN public.users owner ON owner.id = m_owner.user_id "
                + whereClause
                + " GROUP BY t.id ORDER BY t.created_at DESC",
                params.toArray());
        List<UUID> out = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            UUID id = UUID.fromString(c.get("id").toString());
            Object schema = c.get("schema_name");
            if (schema == null || !TenantSchema.isValid(schema.toString())) continue;
            List<Map<String, Object>> rows = tenantRows(id,
                    "SELECT " + TenantLastActivitySql.expression(schema.toString()) + " AS last_activity",
                    TenantLastActivitySql.args(id));
            Instant last = rows == null || rows.isEmpty() ? null : toInstant(rows.get(0).get("last_activity"));
            if (last == null) continue;
            if (from != null && last.isBefore(from)) continue;
            if (to != null && last.isAfter(to)) continue;
            out.add(id);
        }
        return out;
    }

    /**
     * Plan filter: null/blank/ALL → null (no filter); FREE | PREMIUM_TRIAL | PREMIUM | PAID (legacy,
     * = PREMIUM_TRIAL + PREMIUM) kept; anything else → 422 VALIDATION_ERROR.
     */
    private static String normalizePlanFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String plan = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PLAN_FILTERS.contains(plan)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "plan must be one of ALL, FREE, PREMIUM_TRIAL, PREMIUM, PAID");
        }
        return "ALL".equals(plan) ? null : plan;
    }

    /**
     * Fills storeCount and lastActivityAt from the tenant's own schema. The schema name is
     * resolved by the caller (preloaded map for the list, {@link #loadSchemaName} for the detail),
     * so no per-item {@code schema_name} lookup is made here (Story 11.5). A null or invalid
     * schema name leaves the item with zero metrics.
     */
    private AdminTenantListItem enrichWithCrossSchemaMetrics(AdminTenantListItem item, String schemaName) {
        if (!TenantSchema.isValid(schemaName)) return item; // gate before SQL interpolation

        // Spec 9-1 completion (routed from 11-5) — TWO statements per tenant schema: store count +
        // last activity together, then the owner's name on its own, so a broken employees table
        // only loses the name (« Propriétaire »), never the metrics. The list costs 1 + 2N.
        List<Map<String, Object>> rows = tenantRows(item.id(), "SELECT "
                + "(SELECT COUNT(*) FROM " + schemaName + ".stores WHERE is_active = true) AS store_count, "
                + TenantLastActivitySql.expression(schemaName) + " AS last_activity",
                TenantLastActivitySql.args(item.id()));
        Map<String, Object> row = rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
        Object count = row.get("store_count");
        Instant lastActivity = toInstant(row.get("last_activity"));

        List<Map<String, Object>> ownerRows = tenantRows(item.id(),
                "SELECT " + EMPLOYEE_FULL_NAME + " AS owner_name FROM " + schemaName + ".employees e "
                + "JOIN public.user_tenant_memberships m ON m.user_id = e.user_id "
                + "WHERE m.tenant_id = ? AND m.role = 'OWNER' "
                + "ORDER BY e.created_at LIMIT 1", item.id());
        Object owner = ownerRows == null || ownerRows.isEmpty() ? null : ownerRows.get(0).get("owner_name");
        String ownerName = owner != null && !owner.toString().isBlank()
                ? owner.toString().trim() : AdminLabels.OWNER;

        return new AdminTenantListItem(
                item.id(), item.code(), item.name(), item.ownerPhone(),
                item.plan(), item.status(),
                item.registeredAt(), lastActivity,
                count instanceof Number n ? n.intValue() : 0, item.employeeCount(),
                item.deletionScheduledAt(), ownerName
        );
    }

    /**
     * Runs a query against one tenant's schema. A schema / SQL / data error of this tenant only
     * (missing table, broken schema…) yields {@code null} so the caller skips it; connection or
     * resource failures (pool exhaustion, database down) are rethrown, never hidden as "skip".
     */
    private List<Map<String, Object>> tenantRows(UUID tenantId, String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            // tenant id only — never the schema name or SQL in logs
            log.warn("Admin tenants: tenant {} schema query skipped ({})", tenantId, e.getClass().getSimpleName());
            return null;
        }
    }

    private static void rethrowIfResourceFailure(DataAccessException e) {
        if (e instanceof DataAccessResourceFailureException
                || e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException) {
            throw e;
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }

    /**
     * Loads the schema names of a page of tenants in a single parameterized {@code IN (…)} query.
     * A SQL/data failure yields an empty map (items kept with zero metrics); a connection or
     * resource failure (database down, pool exhausted) is rethrown.
     */
    private Map<UUID, String> loadSchemaNames(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String inClause = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<UUID, String> out = new HashMap<>();
        try {
            jdbc.query("SELECT id, schema_name FROM public.tenants WHERE id IN (" + inClause + ")",
                    (RowCallbackHandler) rs -> out.put(
                            UUID.fromString(rs.getString("id")), rs.getString("schema_name")),
                    ids.toArray());
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            log.warn("Batched schema_name lookup failed for {} tenants — metrics left empty ({})",
                    ids.size(), e.getClass().getSimpleName());
            return Map.of();
        }
        return out;
    }

    private String loadSchemaName(UUID tenantId) {
        String s = jdbc.queryForObject(
                "SELECT schema_name FROM public.tenants WHERE id = ?",
                String.class, tenantId);
        if (s == null) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND,
                    "Tenant schema not found: " + tenantId);
        }
        if (!TenantSchema.isValid(s)) {
            // gate before SQL interpolation — a corrupted row answers like an unknown tenant
            // (404), never a 500 (follow-up review v1s-13-12); the raw value is not echoed.
            log.warn("Tenant {} has an invalid schema_name (length={}) — detail refused", tenantId, s.length());
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant schema not found: " + tenantId);
        }
        return s;
    }

    private List<AdminStoreInfo> loadStores(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return List.of();
        }
        try {
            return jdbc.query(
                    "SELECT id::text, name, type AS store_type, 0 AS total_revenue FROM " + schema + ".stores WHERE is_active = true ORDER BY name",
                    (rs, row) -> new AdminStoreInfo(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("store_type"),
                            rs.getLong("total_revenue")
                    ));
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            return List.of();
        }
    }

    /**
     * Members of the tenant (OWNER, EMPLOYEE) read from {@code public.*} with their last active day
     * from {@code public.user_daily_activity} (Story 9-4), then their names from
     * {@code <schema>.employees} in a separate fail-soft statement: a broken tenant table only
     * loses the names (« Propriétaire » / « Employé »), never the members. No user id leaves here.
     */
    private List<AdminEmployeeInfo> loadEmployees(String schema, UUID tenantId) {
        record Member(UUID userId, String role, LocalDate lastDay) {}
        List<Member> members = jdbc.query(
                "SELECT m.user_id, m.role, "
                + "(SELECT MAX(d.activity_date) FROM public.user_daily_activity d "
                + "  WHERE d.user_id = m.user_id AND d.tenant_id = m.tenant_id) AS last_day "
                + "FROM public.user_tenant_memberships m "
                + "WHERE m.tenant_id = ? AND m.role IN ('OWNER','EMPLOYEE')",
                (rs, row) -> {
                    java.sql.Date day = rs.getDate("last_day");
                    String uid = rs.getString("user_id");
                    return new Member(uid != null ? UUID.fromString(uid) : null, rs.getString("role"),
                            day != null ? day.toLocalDate() : null);
                },
                tenantId);
        Map<UUID, String> names = loadEmployeeNames(schema, tenantId,
                members.stream().map(Member::userId).filter(java.util.Objects::nonNull).toList());
        return members.stream()
                .map(m -> new AdminEmployeeInfo(memberName(names.get(m.userId()), m.role()), m.role(), m.lastDay()))
                .sorted(java.util.Comparator
                        .comparing((AdminEmployeeInfo e) -> "OWNER".equals(e.role()) ? 0 : 1)
                        .thenComparing(AdminEmployeeInfo::name))
                .toList();
    }

    /** Names (trimmed first + last) of the given users in the tenant's employees table; fail-soft. */
    private Map<UUID, String> loadEmployeeNames(String schema, UUID tenantId, List<UUID> userIds) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return Map.of();
        }
        if (userIds.isEmpty()) return Map.of();
        List<Map<String, Object>> rows = tenantRows(tenantId,
                "SELECT e.user_id, " + EMPLOYEE_FULL_NAME + " AS full_name FROM " + schema + ".employees e "
                + "WHERE e.user_id IN (" + userIds.stream().map(x -> "?").collect(Collectors.joining(",")) + ") "
                + "ORDER BY e.created_at",
                userIds.toArray());
        Map<UUID, String> out = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> r : rows) {
            Object uid = r.get("user_id");
            Object name = r.get("full_name");
            if (uid != null && name != null) out.putIfAbsent(UUID.fromString(uid.toString()), name.toString().trim());
        }
        return out;
    }

    /** Stored name, else « Propriétaire » for an owner, « Employé » for an employee. */
    static String memberName(String fullName, String role) {
        if (fullName != null && !fullName.isBlank()) return fullName.trim();
        return "OWNER".equals(role) ? AdminLabels.OWNER : AdminLabels.EMPLOYEE;
    }

    /** Audit actor: « Système » for a SYSTEM event, else the employee name, « Propriétaire », or unknown. */
    static String actorName(String source, String fullName, boolean isOwner) {
        if ("SYSTEM".equals(source)) return AdminLabels.SYSTEM;
        if (fullName != null && !fullName.isBlank()) return fullName.trim();
        return isOwner ? AdminLabels.OWNER : AdminLabels.UNKNOWN_USER;
    }

    /** Story 9-2 — current subscription (first row by created_at), null when unreadable. */
    /** Story 9-2 — suspension trace from {@code public.tenants}; null when not suspended. */
    private AdminSuspensionInfo loadSuspension(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT suspended_at, suspended_reason FROM public.tenants "
                + "WHERE id = ? AND status = 'SUSPENDED' AND suspended_at IS NOT NULL", tenantId);
        if (rows == null || rows.isEmpty()) return null;
        Object at = rows.get(0).get("suspended_at");
        Instant suspendedAt = at instanceof Timestamp ts ? ts.toInstant()
                : at instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
        return suspendedAt == null ? null
                : new AdminSuspensionInfo(suspendedAt, (String) rows.get(0).get("suspended_reason"));
    }

    private AdminPlanInfo loadPlan(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return null;
        }
        try {
            List<AdminPlanInfo> rows = jdbc.query(
                    "SELECT plan_type, expires_at FROM \"" + schema + "\".subscriptions "
                    + "ORDER BY created_at ASC LIMIT 1",
                    (rs, row) -> {
                        Timestamp exp = rs.getTimestamp("expires_at");
                        return new AdminPlanInfo(rs.getString("plan_type"),
                                exp != null ? exp.toInstant() : null);
                    });
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            // tenant id only — never the schema name or SQL in logs
            log.warn("Tenant detail: subscription of tenant {} unreadable", tenantId, e);
            return null;
        }
    }

    private long countAuditEvents(String schema, UUID tenantId) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return 0L;
        }
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".audit_log "
                    + "WHERE occurred_at >= NOW() - INTERVAL '30 days'",
                    Long.class);
            return count != null ? count : 0L;
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            return 0L;
        }
    }

    /**
     * Audit lines of the last 30 days, newest first, from the enriched audit log (v1s-18-6): action
     * and entity type codes (labelled by the dashboard), {@code entity_label}, store name and actor
     * name — never {@code entity_id} nor {@code user_id}.
     */
    private List<AdminAuditEvent> loadAuditEvents(String schema, UUID tenantId, int page) {
        if (!TenantSchema.isValid(schema)) { // fail-soft like the SQL errors below; no SQL built
            log.warn("Tenant detail: tenant {} section skipped (invalid schema name)", tenantId);
            return List.of();
        }
        record AuditRow(String action, String entityType, String entityLabel, String source,
                        UUID userId, boolean isOwner, String storeName, Instant occurredAt) {}
        List<AuditRow> rows;
        try {
            rows = jdbc.query(
                    "SELECT a.action, a.entity_type, a.entity_label, a.occurred_at, a.source, a.user_id, "
                    + "st.name AS store_name, "
                    + "EXISTS (SELECT 1 FROM public.user_tenant_memberships m "
                    + "        WHERE m.user_id = a.user_id AND m.tenant_id = ? AND m.role = 'OWNER') AS actor_is_owner "
                    + "FROM " + schema + ".audit_log a "
                    + "LEFT JOIN " + schema + ".stores st ON st.id = a.store_id "
                    + "WHERE a.occurred_at >= NOW() - INTERVAL '30 days' "
                    + "ORDER BY a.occurred_at DESC "
                    + "LIMIT ? OFFSET ?",
                    (rs, row) -> {
                        Timestamp at = rs.getTimestamp("occurred_at");
                        String uid = rs.getString("user_id");
                        return new AuditRow(
                                rs.getString("action"),
                                rs.getString("entity_type"),
                                blankToNull(rs.getString("entity_label")),
                                rs.getString("source"),
                                uid != null ? UUID.fromString(uid) : null,
                                rs.getBoolean("actor_is_owner"),
                                blankToNull(rs.getString("store_name")),
                                at != null ? at.toInstant() : null);
                    },
                    tenantId, AUDIT_PAGE_SIZE, (long) page * AUDIT_PAGE_SIZE);
        } catch (DataAccessException e) {
            rethrowIfResourceFailure(e);
            log.warn("Tenant detail: audit log of tenant {} unreadable ({})", tenantId, e.getClass().getSimpleName());
            return List.of();
        }
        // Actor names in a separate fail-soft statement: a broken employees table keeps the lines.
        Map<UUID, String> names = loadEmployeeNames(schema, tenantId, rows.stream()
                .filter(r -> !"SYSTEM".equals(r.source()))
                .map(AuditRow::userId).filter(java.util.Objects::nonNull).distinct().toList());
        return rows.stream()
                .map(r -> new AdminAuditEvent(r.action(), r.entityType(), r.entityLabel(),
                        actorName(r.source(), r.userId() != null ? names.get(r.userId()) : null, r.isOwner()),
                        r.storeName(), r.occurredAt()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
            // Story 9-2 — raw plan code (labelled by the dashboard); public.tenants.plan_type is now
            // kept in step with the tenant-schema subscription.
            String plan = rs.getString("plan_type");

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
