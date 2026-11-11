package com.keevo.admin.sync_monitoring.application.service;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.sync_monitoring.domain.model.*;
import com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
    @Mock CrossTenantSql crossTenant;

    AdminSyncMonitoringService service;

    /** Story 19.11c — per schema: {failed ops, conflicts} answered by the union; devices by the GROUP BY. */
    private final java.util.Map<String, long[]> ops = new java.util.HashMap<>();
    private final java.util.Map<String, Object[]> devices = new java.util.HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdminSyncMonitoringService(jdbc, crossTenant);
        lenient().when(crossTenant.withPresence(anyList())).thenAnswer(inv -> ((List<TenantBranch>) inv.getArgument(0))
                .stream().map(t -> t.withTables(java.util.EnumSet.allOf(TenantTable.class))).toList());
        lenient().when(crossTenant.query(anyList(), any(), any(), any())).thenAnswer(inv -> {
            List<Object> rows = new java.util.ArrayList<>();
            for (TenantBranch t : (List<TenantBranch>) inv.getArgument(0)) {
                long[] c = ops.get(t.schemaName());
                if (c != null) rows.add(new Object[]{t.id(), c[0], c[1]});
            }
            return rows;
        });
        lenient().doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (var e : devices.entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("tenant_id")).thenReturn(e.getKey());
                when(rs.getLong("devices")).thenReturn((Long) e.getValue()[0]);
                when(rs.getTimestamp("last_push")).thenReturn((Timestamp) e.getValue()[1]);
                when(rs.getTimestamp("last_pull")).thenReturn((Timestamp) e.getValue()[2]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("GROUP BY tenant_id"), any(RowCallbackHandler.class));
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

        stubTenantOps("kv_aaaaaa", 3L, 0L);
        stubTenantOps("kv_bbbbbb", 7L, 6L);

        AdminSyncOverview result = service.getSyncOverview();

        assertThat(result.totalFailedOps7d()).isEqualTo(10L);
        assertThat(result.totalConflicts7d()).isEqualTo(6L);
        assertThat(result.tenantsInAlert()).isEqualTo(1);
        // 19.11c: one union for every tenant (never a count per schema)
        verify(crossTenant, times(1)).query(anyList(), any(), any(), any());
        verify(jdbc, never()).queryForObject(contains("sync_operations_log"), eq(Long.class));
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
    @DisplayName("getTenantDetail merges employee names and membership roles by user id")
    void getTenantDetail_mergesUserNameAndRole() {
        UUID tenantId = UUID.randomUUID();
        String emp = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String unknown = UUID.randomUUID().toString();
        stubSingleTenant(tenantId.toString(), "Boutique Test", "kv_test00", "PREMIUM");
        stubDevices(List.of(new String[]{"device-emp", emp}, new String[]{"device-own", owner},
                new String[]{"device-unk", unknown}));
        stubEmptyOpsAndConflicts();

        when(jdbc.query(contains("public.user_tenant_memberships m"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapPairs(inv.getArgument(1), "user_role",
                        new String[]{emp, "EMPLOYEE"}, new String[]{owner, "OWNER"}));
        when(jdbc.query(contains("kv_test00.employees e"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapPairs(inv.getArgument(1), "user_name", new String[]{emp, "  Awa Diallo "}));

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.devices()).extracting(AdminDeviceInfo::deviceId)
                .containsExactly("device-emp", "device-own", "device-unk");
        assertThat(detail.devices()).extracting(AdminDeviceInfo::userName)
                .containsExactly("Awa Diallo", null, null);
        assertThat(detail.devices()).extracting(AdminDeviceInfo::userRole)
                .containsExactly("EMPLOYEE", "OWNER", null);
    }

    @Test
    @DisplayName("getTenantDetail keeps the devices (null name/role) when the name and role lookups fail")
    void getTenantDetail_failingLabelLookup_keepsDevices() {
        UUID tenantId = UUID.randomUUID();
        stubSingleTenant(tenantId.toString(), "Boutique Test", "kv_test00", "FREE");
        stubDevices(List.<String[]>of(new String[]{"device-001", UUID.randomUUID().toString()},
                new String[]{"device-002", UUID.randomUUID().toString()}));
        stubEmptyOpsAndConflicts();
        when(jdbc.query(contains("public.user_tenant_memberships m"), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("cast failed"));
        when(jdbc.query(contains("kv_test00.employees e"), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("relation kv_test00.employees does not exist"));

        AdminSyncTenantDetail detail = service.execute(new GetSyncTenantDetailQuery(tenantId));

        assertThat(detail.devices()).extracting(AdminDeviceInfo::deviceId)
                .containsExactly("device-001", "device-002");
        assertThat(detail.devices()).extracting(AdminDeviceInfo::userName).containsOnlyNulls();
        assertThat(detail.devices()).extracting(AdminDeviceInfo::userRole).containsOnlyNulls();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubDevices(List<String[]> rows) {
        when(jdbc.query(contains("FROM public.user_sync_state WHERE tenant_id"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper rm = inv.getArgument(1);
                    List<Object> result = new java.util.ArrayList<>();
                    for (String[] row : rows) {
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("device_id")).thenReturn(row[0]);
                        when(rs.getString("user_id")).thenReturn(row[1]);
                        result.add(rm.mapRow(rs, result.size()));
                    }
                    return result;
                });
    }

    private void stubEmptyOpsAndConflicts() {
        lenient().when(jdbc.query(contains("sync_operations_log"), any(RowMapper.class)))
                .thenReturn(List.of());
        lenient().when(jdbc.query(contains("sync_conflicts_log"), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> mapPairs(RowMapper rm, String valueColumn, String[]... pairs) throws java.sql.SQLException {
        List<Object> result = new java.util.ArrayList<>();
        for (String[] pair : pairs) {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("user_id")).thenReturn(pair[0]);
            when(rs.getString(valueColumn)).thenReturn(pair[1]);
            result.add(rm.mapRow(rs, result.size()));
        }
        return result;
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
        devices.put(schema, new Object[]{count, lastPush != null ? Timestamp.from(lastPush) : null,
                lastPull != null ? Timestamp.from(lastPull) : null});
    }

    private void stubTenantOps(String schema, long failedOps, long conflicts) {
        ops.put(schema, new long[]{failedOps, conflicts});
    }

    @Test
    @DisplayName("19.11c — list: one union for the counts, one GROUP BY for the devices; branch SQL per schema")
    @SuppressWarnings("unchecked")
    void listTenantSyncHealth_setBased() {
        stubTenants(List.of(
                tenantRow("11111111-1111-1111-1111-111111111111", "A", "kv_aaaaaa", "FREE"),
                tenantRow("22222222-2222-2222-2222-222222222222", "B", "kv_bbbbbb", "FREE")));
        stubTenantOps("kv_aaaaaa", 1L, 0L);

        List<AdminTenantSyncHealth> result = service.listTenantSyncHealth();

        assertThat(result).hasSize(2); // B unread (skipped by the union) is still listed, with zeros
        org.mockito.ArgumentCaptor<List<TenantBranch>> tenants = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<CrossTenantSql.BranchSql> branch =
                org.mockito.ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant).query(tenants.capture(), branch.capture(), any(), any());
        String sql = branch.getValue().sql(tenants.getValue().get(0), new java.util.ArrayList<>());
        assertThat(sql).contains("kv_aaaaaa.sync_operations_log").contains("kv_aaaaaa.sync_conflicts_log");
        String withoutConflicts = branch.getValue().sql(tenants.getValue().get(0).withTables(
                java.util.Set.of(TenantTable.SYNC_OPS_STATUS)), new java.util.ArrayList<>());
        assertThat(withoutConflicts).doesNotContain("sync_conflicts_log");
        verify(jdbc, times(1)).query(contains("GROUP BY tenant_id"), any(RowCallbackHandler.class));
        verify(jdbc, never()).queryForObject(contains("user_sync_state WHERE tenant_id"), eq(Long.class), any(Object[].class));
    }
}
