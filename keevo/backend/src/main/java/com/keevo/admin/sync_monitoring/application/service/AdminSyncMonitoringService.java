package com.keevo.admin.sync_monitoring.application.service;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.admin.sync_monitoring.domain.port.in.*;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AdminSyncMonitoringService — cross-tenant sync health aggregator.
 *
 * <p>Uses raw JdbcTemplate with explicit schema-qualified queries. Never sets TenantContext.
 *
 * <p>Key schema facts:
 * - {@code public.user_sync_state.tenant_id} stores the tenant schema name (e.g. "kv_abc123")
 * - {@code sync_operations_log} and {@code sync_conflicts_log} are per-tenant tables
 *
 * <p>Deliberately NOT {@code @Transactional}: each per-tenant statement autocommits, so one broken
 * tenant schema cannot abort (25P02) the PostgreSQL transaction of the following tenants' queries.
 *
 * <p>Story 19.11c (FUS-NFR11): the overview and the list never run a statement per tenant — the active tenants in
 * one query, their table presence in one catalogue query, the 7-day failed operations and conflicts of every tenant
 * in chunked {@code UNION ALL} statements ({@link CrossTenantSql}, 100 tenants each; a missing table counts 0, a
 * broken schema is skipped with a WARN, a connection failure fails the call), the devices of every tenant in one
 * {@code GROUP BY} on {@code public.user_sync_state}.
 */
@Service
public class AdminSyncMonitoringService
        implements GetSyncOverviewUseCase, ListTenantSyncHealthUseCase, GetSyncTenantDetailUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminSyncMonitoringService.class);

    private final JdbcTemplate jdbc;
    private final CrossTenantSql crossTenant;

    /** With cross-tenant statements of the default chunk size (tests). */
    public AdminSyncMonitoringService(JdbcTemplate jdbc) {
        this(jdbc, new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminSyncMonitoringService(JdbcTemplate jdbc, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.crossTenant = crossTenant;
    }

    // ── GetSyncOverviewUseCase ────────────────────────────────────────────────

    @Override
    public AdminSyncOverview getSyncOverview() {
        long totalDevices = safeCount("SELECT COUNT(*) FROM public.user_sync_state");
        Instant latestPush = safeMaxInstant("SELECT MAX(last_push_at) FROM public.user_sync_state");

        long totalFailedOps7d = 0;
        long totalConflicts7d = 0;
        int tenantsInAlert = 0;

        for (long[] counts : syncCounts(loadActiveTenantSchemas()).values()) {
            totalFailedOps7d += counts[0];
            totalConflicts7d += counts[1];
            if (counts[0] > 10 || counts[1] > 5) tenantsInAlert++;
        }

        return new AdminSyncOverview(totalDevices, totalFailedOps7d, totalConflicts7d, tenantsInAlert, latestPush);
    }

    // ── ListTenantSyncHealthUseCase ───────────────────────────────────────────

    @Override
    public List<AdminTenantSyncHealth> listTenantSyncHealth() {
        List<TenantSchemaRef> tenants = loadActiveTenantSchemas();
        Map<String, long[]> counts = syncCounts(tenants);
        Map<String, DeviceStats> devices = deviceStats();

        List<AdminTenantSyncHealth> items = tenants.stream().map(tenant -> {
            long[] c = counts.getOrDefault(tenant.id(), new long[2]);
            DeviceStats d = devices.getOrDefault(tenant.schemaName(), DeviceStats.NONE);
            String status = computeStatus(c[0], c[1], d.lastPush());
            return new AdminTenantSyncHealth(
                    tenant.id(), tenant.name(), tenant.planType(),
                    c[0], c[1], (int) d.count(),
                    d.lastPush(), d.lastPull(), status);
        }).toList();

        Map<String, Integer> statusOrder = Map.of("ERROR", 0, "WARN", 1, "OK", 2);
        return items.stream()
                .sorted(Comparator.comparingInt((AdminTenantSyncHealth h) ->
                        statusOrder.getOrDefault(h.status(), 9))
                        .thenComparing(AdminTenantSyncHealth::tenantName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // ── GetSyncTenantDetailUseCase ────────────────────────────────────────────

    @Override
    public AdminSyncTenantDetail execute(GetSyncTenantDetailQuery query) {
        TenantSchemaRef tenant = loadTenantById(query.tenantId().toString());
        TenantSchema.validate(tenant.schemaName()); // request path: loadTenantById() already refuses invalid rows

        List<AdminDeviceInfo> rawDevices = safeQuery(tenant.id(),
                "SELECT device_id, user_id, last_push_at, last_pull_at, updated_at "
                + "FROM public.user_sync_state WHERE tenant_id = ? ORDER BY last_push_at DESC NULLS LAST",
                deviceMapper(), tenant.schemaName());

        List<AdminSyncFailedOp> failedOps = safeQuery(tenant.id(),
                "SELECT operation_type, error_reason, processed_at "
                + "FROM " + tenant.schemaName() + ".sync_operations_log "
                + "WHERE status = 'FAILED' ORDER BY processed_at DESC LIMIT 10",
                failedOpMapper());

        List<AdminSyncConflict> conflicts = safeQuery(tenant.id(),
                "SELECT entity_type, conflict_type, strategy, resolved_at "
                + "FROM " + tenant.schemaName() + ".sync_conflicts_log "
                + "ORDER BY resolved_at DESC LIMIT 10",
                conflictMapper());

        // Optional label lookups: each statement autocommits (service not transactional), so a failing
        // lookup only leaves the labels null and never affects the sections above.
        List<AdminDeviceInfo> devices = withUserLabels(rawDevices, tenant);

        return new AdminSyncTenantDetail(
                tenant.id(), tenant.name(), tenant.planType(),
                devices, failedOps, conflicts);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * 9-8: human label of each device's user — employee name (tenant schema) and membership role.
     * Resolved by user_id in two lookups separate from the device query: a failing lookup (missing
     * table, bad cast…) only leaves {@code userName}/{@code userRole} null, never drops devices.
     * Only an active membership yields a role; an employee's ACTIVE row wins over older inactive ones.
     * Schema name already validated by {@code loadTenantById} (TenantSchema.isValid).
     */
    private List<AdminDeviceInfo> withUserLabels(List<AdminDeviceInfo> devices, TenantSchemaRef tenant) {
        if (devices.isEmpty() || !TenantSchema.isValid(tenant.schemaName())) return devices;

        Map<String, String> roles = new HashMap<>();
        safeQuery(tenant.id(),
                "SELECT DISTINCT ON (m.user_id) m.user_id::text AS user_id, m.role AS user_role "
                + "FROM public.user_tenant_memberships m "
                + "WHERE m.tenant_id = ?::uuid AND m.is_active "
                + "AND m.user_id IN (SELECT s.user_id FROM public.user_sync_state s WHERE s.tenant_id = ?) "
                + "ORDER BY m.user_id, (m.role = 'OWNER') DESC",
                (rs, row) -> new String[]{rs.getString("user_id"), rs.getString("user_role")},
                tenant.id(), tenant.schemaName())
                .forEach(r -> roles.put(r[0], blankToNull(r[1])));

        Map<String, String> names = new HashMap<>();
        safeQuery(tenant.id(),
                "SELECT DISTINCT ON (e.user_id) e.user_id::text AS user_id, "
                + "NULLIF(TRIM(CONCAT_WS(' ', TRIM(e.first_name), TRIM(e.last_name))), '') AS user_name "
                + "FROM " + tenant.schemaName() + ".employees e "
                + "WHERE e.user_id IN (SELECT s.user_id FROM public.user_sync_state s WHERE s.tenant_id = ?) "
                + "ORDER BY e.user_id, (e.status = 'ACTIVE') DESC, e.created_at, e.id",
                (rs, row) -> new String[]{rs.getString("user_id"), rs.getString("user_name")},
                tenant.schemaName())
                .forEach(r -> names.put(r[0], blankToNull(r[1])));

        return devices.stream()
                .map(d -> new AdminDeviceInfo(
                        d.deviceId(), d.userId(), d.lastPushAt(), d.lastPullAt(), d.updatedAt(),
                        names.get(d.userId()), roles.get(d.userId())))
                .toList();
    }

    private record TenantSchemaRef(String id, String schemaName, String name, String planType) {}

    /** Devices of one tenant: count, latest push and pull. */
    private record DeviceStats(long count, Instant lastPush, Instant lastPull) {
        static final DeviceStats NONE = new DeviceStats(0, null, null);
    }

    /**
     * 7-day {failed operations, conflicts} by tenant id — one presence query and one union per 100 tenants; a table
     * absent from a schema counts 0; a tenant whose schema fails is absent (counts 0, WARN in {@link CrossTenantSql}).
     */
    private Map<String, long[]> syncCounts(List<TenantSchemaRef> tenants) {
        List<TenantBranch> branches = crossTenant.withPresence(tenants.stream()
                .map(t -> new TenantBranch(t.id(), t.name(), t.schemaName(), "ACTIVE", null)).toList());
        Map<String, long[]> out = new HashMap<>();
        crossTenant.query(branches, (tenant, params) -> {
                    String schema = TenantSchema.validate(tenant.schemaName());
                    String failed = tenant.has(TenantTable.SYNC_OPS_STATUS)
                            ? "(SELECT COUNT(*) FROM " + schema + ".sync_operations_log "
                              + "WHERE status = 'FAILED' AND processed_at >= NOW() - INTERVAL '7 days')"
                            : "CAST(0 AS bigint)";
                    String conflicts = tenant.has(TenantTable.SYNC_CONFLICTS)
                            ? "(SELECT COUNT(*) FROM " + schema + ".sync_conflicts_log "
                              + "WHERE resolved_at >= NOW() - INTERVAL '7 days')"
                            : "CAST(0 AS bigint)";
                    return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, " + failed
                            + " AS failed, " + conflicts + " AS conflicts";
                }, union -> "SELECT * FROM (" + union + ") u",
                (rs, n) -> new Object[]{rs.getString("tenant_id"), rs.getLong("failed"), rs.getLong("conflicts")})
                .forEach(r -> out.put((String) r[0], new long[]{(Long) r[1], (Long) r[2]}));
        return out;
    }

    /**
     * Devices of every tenant, keyed by schema name ({@code user_sync_state.tenant_id}) — one statement. A SQL error of
     * this read leaves the device columns empty (as the former per-tenant reads did); a connection failure fails.
     */
    private Map<String, DeviceStats> deviceStats() {
        Map<String, DeviceStats> out = new HashMap<>();
        try {
            readDeviceStats(out);
        } catch (org.springframework.dao.DataAccessResourceFailureException
                 | org.springframework.dao.TransientDataAccessException
                 | org.springframework.dao.RecoverableDataAccessException e) {
            throw e;
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Sync monitoring: device figures unreadable ({})", e.getClass().getSimpleName());
            return Map.of();
        }
        return out;
    }

    private void readDeviceStats(Map<String, DeviceStats> out) {
        jdbc.query("SELECT tenant_id, COUNT(*) AS devices, MAX(last_push_at) AS last_push, "
                        + "MAX(last_pull_at) AS last_pull FROM public.user_sync_state GROUP BY tenant_id",
                (RowCallbackHandler) rs -> out.put(rs.getString("tenant_id"), new DeviceStats(rs.getLong("devices"),
                        toInstant(rs, "last_push"), toInstant(rs, "last_pull"))));
    }

    private List<TenantSchemaRef> loadActiveTenantSchemas() {
        // Follow-up review v1s-13-12: gate before the schema name is interpolated into SQL.
        return jdbc.query(
                "SELECT id::text, schema_name, name, plan_type FROM public.tenants "
                + "WHERE id <> '00000000-0000-0000-0000-000000000000'::uuid AND status = 'ACTIVE' "
                + "ORDER BY name",
                (rs, row) -> new TenantSchemaRef(
                        rs.getString("id"), rs.getString("schema_name"),
                        rs.getString("name"), rs.getString("plan_type")))
                .stream()
                .filter(t -> {
                    if (TenantSchema.isValid(t.schemaName())) return true;
                    log.warn("Sync monitoring: tenant {} skipped (invalid schema name)", t.id());
                    return false;
                })
                .toList();
    }

    private TenantSchemaRef loadTenantById(String tenantUuid) {
        List<TenantSchemaRef> results = jdbc.query(
                "SELECT id::text, schema_name, name, plan_type FROM public.tenants WHERE id = ?::uuid",
                (rs, row) -> new TenantSchemaRef(
                        rs.getString("id"), rs.getString("schema_name"),
                        rs.getString("name"), rs.getString("plan_type")),
                tenantUuid);
        if (results.isEmpty()
                || !TenantSchema.isValid(results.get(0).schemaName())) {
            throw new IllegalArgumentException("Tenant not found: " + tenantUuid);
        }
        return results.get(0);
    }

    private String computeStatus(long failed, long conflicts, Instant lastPush) {
        if (failed > 10 || conflicts > 5) return "ERROR";
        boolean pushStale = lastPush == null || lastPush.isBefore(Instant.now().minus(48, ChronoUnit.HOURS));
        if (failed > 0 || pushStale) return "WARN";
        return "OK";
    }

    private long safeCount(String sql, Object... args) {
        try {
            Long result = args.length == 0
                    ? jdbc.queryForObject(sql, Long.class)
                    : jdbc.queryForObject(sql, Long.class, args);
            return result != null ? result : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Instant safeMaxInstant(String sql, Object... args) {
        try {
            Timestamp ts = args.length == 0
                    ? jdbc.queryForObject(sql, Timestamp.class)
                    : jdbc.queryForObject(sql, Timestamp.class, args);
            return ts != null ? ts.toInstant() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> safeQuery(String tenantId, String sql, RowMapper<T> mapper, Object... args) {
        try {
            return jdbc.query(sql, mapper, args);
        } catch (Exception e) {
            // Tenant id only, never the schema name or SQL (may embed it).
            log.warn("Sync monitoring: tenant {} detail query failed ({})", tenantId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private RowMapper<AdminDeviceInfo> deviceMapper() {
        return (rs, rowNum) -> new AdminDeviceInfo(
                rs.getString("device_id"),
                rs.getString("user_id"),
                toInstant(rs, "last_push_at"),
                toInstant(rs, "last_pull_at"),
                toInstant(rs, "updated_at"),
                null,
                null);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private RowMapper<AdminSyncFailedOp> failedOpMapper() {
        return (rs, rowNum) -> new AdminSyncFailedOp(
                rs.getString("operation_type"),
                rs.getString("error_reason"),
                toInstant(rs, "processed_at"));
    }

    private RowMapper<AdminSyncConflict> conflictMapper() {
        return (rs, rowNum) -> new AdminSyncConflict(
                rs.getString("entity_type"),
                rs.getString("conflict_type"),
                rs.getString("strategy"),
                toInstant(rs, "resolved_at"));
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }
}
