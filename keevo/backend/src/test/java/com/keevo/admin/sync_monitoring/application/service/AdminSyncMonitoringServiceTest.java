package com.keevo.admin.sync_monitoring.application.service;

import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSyncMonitoringService")
class AdminSyncMonitoringServiceTest {

    @Mock JdbcTemplate jdbc;

    AdminSyncMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new AdminSyncMonitoringService(jdbc);
    }

    // ── getSyncOverview ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSyncOverview counts total devices from public schema")
    void getOverview_countsTotalDevicesFromPublicSchema() {
        stubTenants(List.of());
        when(jdbc.queryForObject(contains("COUNT(*) FROM public.user_sync_state"), eq(Long.class)))
                .thenReturn(15L);
        lenient().when(jdbc.queryForObject(contains("MAX(last_push_at)"), eq(Timestamp.class)))
                .thenReturn(null);

        AdminSyncOverview result = service.getSyncOverview();

        assertThat(result.totalDevices()).isEqualTo(15L);
    }

    @Test
    @DisplayName("getSyncOverview aggregates failed ops across all tenant schemas")
    void getOverview_failedOpsAggregatedAcrossSchemas() {
        stubTenants(List.of(
                tenantRow("t1", "Boutique A", "kv_aaaaaa", "FREE"),
                tenantRow("t2", "Boutique B", "kv_bbbbbb", "FREE")));

        lenient().when(jdbc.queryForObject(contains("COUNT(*) FROM public.user_sync_state"), eq(Long.class)))
                .thenReturn(0L);
        lenient().when(jdbc.queryForObject(contains("MAX(last_push_at)"), eq(Timestamp.class)))
                .thenReturn(null);

        when(jdbc.queryForObject(contains("kv_aaaaaa.sync_operations_log"), eq(Long.class)))
                .thenReturn(3L);
        when(jdbc.queryForObject(contains("kv_bbbbbb.sync_operations_log"), eq(Long.class)))
                .thenReturn(7L);
        lenient().when(jdbc.queryForObject(contains("sync_conflicts_log"), eq(Long.class)))
                .thenReturn(0L);

        AdminSyncOverview result = service.getSyncOverview();

        assertThat(result.totalFailedOps7d()).isEqualTo(10L);
    }

    // ── listTenantSyncHealth ──────────────────────────────────────────────────

    @Test
    @DisplayName("listTenantSyncHealth returns ERROR status when failed ops > 10")
    void listTenantSyncHealth_statusERROR_whenFailedOpsExceed10() {
        stubTenants(List.<Object[]>of(tenantRow("t1", "Boutique A", "kv_aaaaaa", "FREE")));
        stubDeviceStats("kv_aaaaaa", 2L, null, null);
        stubTenantOps("kv_aaaaaa", 11L, 0L);

        List<AdminTenantSyncHealth> result = service.listTenantSyncHealth();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("listTenantSyncHealth returns WARN when last push > 48h ago")
    void listTenantSyncHealth_statusWARN_whenPushStaleover48h() {
        stubTenants(List.<Object[]>of(tenantRow("t1", "Boutique A", "kv_aaaaaa", "FREE")));
        Instant stale = Instant.now().minus(50, ChronoUnit.HOURS);
        stubDeviceStats("kv_aaaaaa", 1L, stale, null);
        stubTenantOps("kv_aaaaaa", 0L, 0L);

        List<AdminTenantSyncHealth> result = service.listTenantSyncHealth();

        assertThat(result.get(0).status()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("listTenantSyncHealth returns OK for healthy tenant")
    void listTenantSyncHealth_statusOK_forHealthyTenant() {
        stubTenants(List.<Object[]>of(tenantRow("t1", "Boutique A", "kv_aaaaaa", "FREE")));
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        stubDeviceStats("kv_aaaaaa", 1L, recent, recent);
        stubTenantOps("kv_aaaaaa", 0L, 0L);

        List<AdminTenantSyncHealth> result = service.listTenantSyncHealth();

        assertThat(result.get(0).status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("listTenantSyncHealth sorts ERROR tenants first")
    void listTenantSyncHealth_sortedErrorFirst() {
        stubTenants(List.of(
                tenantRow("t1", "Good Tenant", "kv_good00", "FREE"),
                tenantRow("t2", "Bad Tenant",  "kv_bad000", "FREE")));

        // good: 0 failed ops, recent push
        stubDeviceStats("kv_good00", 1L, Instant.now().minus(1, ChronoUnit.HOURS), null);
        stubTenantOps("kv_good00", 0L, 0L);

        // bad: >10 failed ops → ERROR
        stubDeviceStats("kv_bad000", 1L, Instant.now().minus(1, ChronoUnit.HOURS), null);
        stubTenantOps("kv_bad000", 15L, 0L);

        List<AdminTenantSyncHealth> result = service.listTenantSyncHealth();

        assertThat(result.get(0).status()).isEqualTo("ERROR");
        assertThat(result.get(1).status()).isEqualTo("OK");
    }

    // ── getTenantDetail ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getTenantDetail loads devices from public schema")
    void getTenantDetail_loadsDevicesFromPublicSchema() {
        UUID tenantId = UUID.randomUUID();
        stubSingleTenant(tenantId.toString(), "Boutique Test", "kv_test00", "FREE");

        when(jdbc.query(contains("FROM public.user_sync_state WHERE tenant_id"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<?> rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("device_id")).thenReturn("device-001");
                    when(rs.getString("user_id")).thenReturn(UUID.randomUUID().toString());
                    when(rs.getTimestamp("last_push_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("last_pull_at")).thenReturn(null);
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
                    return List.of(rm.mapRow(rs, 0));
                });
        lenient().when(jdbc.query(contains("sync_operations_log"), any(RowMapper.class)))
                .thenReturn(List.of());
        lenient().when(jdbc.query(contains("sync_conflicts_log"), any(RowMapper.class)))
                .thenReturn(List.of());

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.devices()).hasSize(1);
        assertThat(detail.devices().get(0).deviceId()).isEqualTo("device-001");
    }

    @Test
    @DisplayName("getTenantDetail returns empty lists when schema queries fail")
    void getTenantDetail_schemaFailure_returnsEmptyLists() {
        UUID tenantId = UUID.randomUUID();
        stubSingleTenant(tenantId.toString(), "Boutique Test", "kv_fail00", "FREE");

        when(jdbc.query(contains("FROM public.user_sync_state WHERE tenant_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("kv_fail00.sync_operations_log"), any(RowMapper.class)))
                .thenThrow(new RuntimeException("schema not found"));
        when(jdbc.query(contains("kv_fail00.sync_conflicts_log"), any(RowMapper.class)))
                .thenThrow(new RuntimeException("schema not found"));

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.recentFailedOps()).isEmpty();
        assertThat(detail.recentConflicts()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTenants(List<Object[]> rows) {
        when(jdbc.query(contains("FROM public.tenants"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper rm = inv.getArgument(1);
                    List<Object> result = new java.util.ArrayList<>();
                    for (Object[] row : rows) {
                        try {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getString("id")).thenReturn((String) row[0]);
                            when(rs.getString("name")).thenReturn((String) row[1]);
                            when(rs.getString("schema_name")).thenReturn((String) row[2]);
                            when(rs.getString("plan_type")).thenReturn((String) row[3]);
                            result.add(rm.mapRow(rs, 0));
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }
                    return result;
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubSingleTenant(String id, String name, String schema, String planType) {
        when(jdbc.query(contains("FROM public.tenants"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper rm = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    try {
                        when(rs.getString("id")).thenReturn(id);
                        when(rs.getString("name")).thenReturn(name);
                        when(rs.getString("schema_name")).thenReturn(schema);
                        when(rs.getString("plan_type")).thenReturn(planType);
                        return List.of(rm.mapRow(rs, 0));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
    }

    private Object[] tenantRow(String id, String name, String schema, String planType) {
        return new Object[]{id, name, schema, planType};
    }

    private void stubDeviceStats(String schema, long count, Instant lastPush, Instant lastPull) {
        when(jdbc.queryForObject(
                argThat(s -> s != null && s.contains("COUNT(*) FROM public.user_sync_state") && s.contains("tenant_id")),
                eq(Long.class), eq(schema)))
                .thenReturn(count);

        Timestamp pushTs = lastPush != null ? Timestamp.from(lastPush) : null;
        lenient().when(jdbc.queryForObject(
                argThat(s -> s != null && s.contains("MAX(last_push_at)") && s.contains("tenant_id")),
                eq(Timestamp.class), eq(schema)))
                .thenReturn(pushTs);

        Timestamp pullTs = lastPull != null ? Timestamp.from(lastPull) : null;
        lenient().when(jdbc.queryForObject(
                argThat(s -> s != null && s.contains("MAX(last_pull_at)") && s.contains("tenant_id")),
                eq(Timestamp.class), eq(schema)))
                .thenReturn(pullTs);
    }

    private void stubTenantOps(String schema, long failedOps, long conflicts) {
        lenient().when(jdbc.queryForObject(
                argThat(s -> s != null && s.contains(schema + ".sync_operations_log")),
                eq(Long.class)))
                .thenReturn(failedOps);
        lenient().when(jdbc.queryForObject(
                argThat(s -> s != null && s.contains(schema + ".sync_conflicts_log")),
                eq(Long.class)))
                .thenReturn(conflicts);
    }
}
