package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.alerts.domain.model.AlertSourceRows.ExpiredPlan;
import com.keevo.admin.alerts.domain.model.AlertSourceRows.ReportFailures;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link JdbcAlertSourcesAdapter} per-company reads (Story 19.11c): through {@link CrossTenantSql}
 * (mocked) — never a statement per company; the failure policy of the chunks is tested in {@code CrossTenantSql}'s
 * tests and the SQL on PostgreSQL in {@code PlatformHealthAlertsPostgresIntegrationTest}.
 */
@DisplayName("JdbcAlertSourcesAdapter — per-company reads in unions (Story 19.11c)")
class JdbcAlertSourcesAdapterTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    JdbcTemplate jdbc;
    CrossTenantSql crossTenant;
    JdbcAlertSourcesAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        crossTenant = mock(CrossTenantSql.class);
        adapter = new JdbcAlertSourcesAdapter(jdbc, crossTenant);
        // Live companies by name: « Alpha » (A) then « Beta » (B), plus an invalid schema never queried.
        when(jdbc.query(eq(JdbcAlertSourcesAdapter.LIVE_TENANTS_SQL), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            rows.add(mapper.mapRow(tenantRow(A, "Alpha", "kv_aaa111"), 0));
            rows.add(mapper.mapRow(tenantRow(B, "Beta", "kv_bbb222"), 1));
            rows.add(mapper.mapRow(tenantRow(UUID.randomUUID(), "Cassée", "bad schema"), 2));
            return rows;
        });
        when(crossTenant.withPresence(anyList())).thenAnswer(inv -> ((List<TenantBranch>) inv.getArgument(0))
                .stream().map(t -> t.withTables(EnumSet.allOf(TenantTable.class))).toList());
    }

    @Test
    @DisplayName("expired paying plans: one union for every company, result in company name order")
    @SuppressWarnings("unchecked")
    void expiredPlans_union_nameOrder() {
        Instant expiry = Instant.parse("2026-11-01T00:00:00Z");
        // The union answers B before A: the adapter restores the name order.
        when(crossTenant.query(anyList(), any(), any(), any())).thenReturn(List.of(
                java.util.Map.entry(B.toString(), java.util.Map.entry("PREMIUM", expiry)),
                java.util.Map.entry(A.toString(), java.util.Map.entry("TRIAL", expiry))));

        List<ExpiredPlan> plans = adapter.expiredPlansNotFree(Instant.parse("2026-11-10T00:00:00Z"));

        assertThat(plans).extracting(ExpiredPlan::tenantName).containsExactly("Alpha", "Beta");
        assertThat(plans.get(0).plan()).isEqualTo("TRIAL");
        ArgumentCaptor<List<TenantBranch>> branches = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<CrossTenantSql.BranchSql> branch = ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant).query(branches.capture(), branch.capture(), any(), any());
        assertThat(branches.getValue()).extracting(TenantBranch::schemaName).containsExactly("kv_aaa111", "kv_bbb222");
        List<Object> params = new ArrayList<>();
        String sql = branch.getValue().sql(branches.getValue().get(0), params);
        assertThat(sql).contains("'" + A + "'::text AS tenant_id").contains("\"kv_aaa111\".subscriptions")
                .contains("LIMIT 1");
        assertThat(params).hasSize(3);
    }

    @Test
    @DisplayName("report failures: per-chunk sums, companies with failures and latest attempt merged")
    @SuppressWarnings("unchecked")
    void reportFailures_mergesChunks() {
        Instant early = Instant.parse("2026-11-05T08:00:00Z");
        Instant late = Instant.parse("2026-11-06T08:00:00Z");
        when(crossTenant.query(anyList(), any(), any(), any())).thenReturn(List.of(
                new Object[]{3L, 2, early}, new Object[]{1L, 1, late}, new Object[]{0L, 0, null}));

        ReportFailures failures = adapter.reportFailuresSince(Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(failures.failures()).isEqualTo(4);
        assertThat(failures.companies()).isEqualTo(3);
        assertThat(failures.latestAt()).isEqualTo(late);
        ArgumentCaptor<UnaryOperator<String>> outer = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(crossTenant).query(anyList(), any(), outer.capture(), any());
        assertThat(outer.getValue().apply("U")).contains("SUM(failures)").contains("FILTER (WHERE failures > 0)")
                .contains("MAX(latest)");
    }

    private static ResultSet tenantRow(UUID id, String name, String schema) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id.toString());
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("schema_name")).thenReturn(schema);
        when(rs.getTimestamp(any())).thenReturn(Timestamp.from(Instant.EPOCH));
        return rs;
    }
}
