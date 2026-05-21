package com.keevo.admin.sync_monitoring.application.service;

import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.admin.sync_monitoring.domain.port.in.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
 */
@Service
@Transactional(readOnly = true)
public class AdminSyncMonitoringService
        implements GetSyncOverviewUseCase, ListTenantSyncHealthUseCase, GetSyncTenantDetailUseCase {

    private final JdbcTemplate jdbc;

    public AdminSyncMonitoringService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── GetSyncOverviewUseCase ────────────────────────────────────────────────

    @Override
    public AdminSyncOverview getSyncOverview() {
        long totalDevices = safeCount("SELECT COUNT(*) FROM public.user_sync_state");
        Instant latestPush = safeMaxInstant("SELECT MAX(last_push_at) FROM public.user_sync_state");

        long totalFailedOps7d = 0;
        long totalConflicts7d = 0;
        int tenantsInAlert = 0;

        for (TenantSchemaRef tenant : loadActiveTenantSchemas()) {
            long failed = safeCount(
                    "SELECT COUNT(*) FROM " + tenant.schemaName() + ".sync_operations_log "
                    + "WHERE status = 'FAILED' AND processed_at >= NOW() - INTERVAL '7 days'");
            long conflicts = safeCount(
                    "SELECT COUNT(*) FROM " + tenant.schemaName() + ".sync_conflicts_log "
                    + "WHERE resolved_at >= NOW() - INTERVAL '7 days'");
            totalFailedOps7d += failed;
            totalConflicts7d += conflicts;
            if (failed > 10 || conflicts > 5) tenantsInAlert++;
        }

        return new AdminSyncOverview(totalDevices, totalFailedOps7d, totalConflicts7d, tenantsInAlert, latestPush);
    }

    // ── ListTenantSyncHealthUseCase ───────────────────────────────────────────

    @Override
    public List<AdminTenantSyncHealth> listTenantSyncHealth() {
        List<TenantSchemaRef> tenants = loadActiveTenantSchemas();

        List<AdminTenantSyncHealth> items = tenants.stream().map(tenant -> {
            String schema = tenant.schemaName();

            long deviceCount = safeCount(
                    "SELECT COUNT(*) FROM public.user_sync_state WHERE tenant_id = ?", schema);
            Instant lastPush = safeMaxInstant(
                    "SELECT MAX(last_push_at) FROM public.user_sync_state WHERE tenant_id = ?", schema);
            Instant lastPull = safeMaxInstant(
                    "SELECT MAX(last_pull_at) FROM public.user_sync_state WHERE tenant_id = ?", schema);

            long failedOps7d = safeCount(
                    "SELECT COUNT(*) FROM " + schema + ".sync_operations_log "
                    + "WHERE status = 'FAILED' AND processed_at >= NOW() - INTERVAL '7 days'");
            long conflicts7d = safeCount(
                    "SELECT COUNT(*) FROM " + schema + ".sync_conflicts_log "
                    + "WHERE resolved_at >= NOW() - INTERVAL '7 days'");

            String status = computeStatus(failedOps7d, conflicts7d, lastPush);

            return new AdminTenantSyncHealth(
                    tenant.id(), tenant.name(), tenant.planType(),
                    failedOps7d, conflicts7d, (int) deviceCount,
                    lastPush, lastPull, status);
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

        List<AdminDeviceInfo> devices = safeQuery(
                "SELECT device_id, user_id, last_push_at, last_pull_at, updated_at "
                + "FROM public.user_sync_state WHERE tenant_id = ? ORDER BY last_push_at DESC NULLS LAST",
                deviceMapper(), tenant.schemaName());

        List<AdminSyncFailedOp> failedOps = safeQuery(
                "SELECT operation_type, error_reason, processed_at "
                + "FROM " + tenant.schemaName() + ".sync_operations_log "
                + "WHERE status = 'FAILED' ORDER BY processed_at DESC LIMIT 10",
                failedOpMapper());

        List<AdminSyncConflict> conflicts = safeQuery(
                "SELECT entity_type, conflict_type, strategy, resolved_at "
                + "FROM " + tenant.schemaName() + ".sync_conflicts_log "
                + "ORDER BY resolved_at DESC LIMIT 10",
                conflictMapper());

        return new AdminSyncTenantDetail(
                tenant.id(), tenant.name(), tenant.planType(),
                devices, failedOps, conflicts);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private record TenantSchemaRef(String id, String schemaName, String name, String planType) {}

    private List<TenantSchemaRef> loadActiveTenantSchemas() {
        return jdbc.query(
                "SELECT id::text, schema_name, name, plan_type FROM public.tenants "
                + "WHERE id <> '00000000-0000-0000-0000-000000000000'::uuid AND status = 'ACTIVE' "
                + "ORDER BY name",
                (rs, row) -> new TenantSchemaRef(
                        rs.getString("id"), rs.getString("schema_name"),
                        rs.getString("name"), rs.getString("plan_type")));
    }

    private TenantSchemaRef loadTenantById(String tenantUuid) {
        List<TenantSchemaRef> results = jdbc.query(
                "SELECT id::text, schema_name, name, plan_type FROM public.tenants WHERE id = ?::uuid",
                (rs, row) -> new TenantSchemaRef(
                        rs.getString("id"), rs.getString("schema_name"),
                        rs.getString("name"), rs.getString("plan_type")),
                tenantUuid);
        if (results.isEmpty()) {
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

    private <T> List<T> safeQuery(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return jdbc.query(sql, mapper, args);
        } catch (Exception e) {
            return List.of();
        }
    }

    private RowMapper<AdminDeviceInfo> deviceMapper() {
        return (rs, rowNum) -> new AdminDeviceInfo(
                rs.getString("device_id"),
                rs.getString("user_id"),
                toInstant(rs, "last_push_at"),
                toInstant(rs, "last_pull_at"),
                toInstant(rs, "updated_at"));
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
