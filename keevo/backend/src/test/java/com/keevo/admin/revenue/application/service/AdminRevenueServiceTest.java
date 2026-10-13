package com.keevo.admin.revenue.application.service;

import com.keevo.admin.revenue.domain.model.*;
import com.keevo.admin.revenue.domain.port.in.RevenueRangeQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminRevenueService")
class AdminRevenueServiceTest {

    /** 2026-10-13 11:00 in Douala (WAT = UTC+1). */
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final String T1 = UUID.randomUUID().toString();
    private static final String T2 = UUID.randomUUID().toString();
    private static final String T3 = UUID.randomUUID().toString();
    private static final String T4 = UUID.randomUUID().toString();

    @Mock JdbcTemplate jdbc;

    AdminRevenueService service;
    List<RevenuePayment> payments;
    List<PlanChange> events;

    @BeforeEach
    void setUp() {
        service = new AdminRevenueService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        payments = new ArrayList<>();
        events = new ArrayList<>();
        when(jdbc.query(eq(AdminRevenueService.PAYMENTS_SQL), any(RowMapper.class))).thenAnswer(i -> payments);
        when(jdbc.query(eq(AdminRevenueService.EVENTS_SQL), any(RowMapper.class))).thenAnswer(i -> events);
        when(jdbc.queryForList(AdminRevenueService.PLAN_TENANTS_SQL)).thenReturn(List.of());
    }

    private void tenants(Object... idSchemaPairs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < idSchemaPairs.length; i += 2) {
            rows.add(Map.of("id", idSchemaPairs[i], "schema_name", idSchemaPairs[i + 1]));
        }
        when(jdbc.queryForList(AdminRevenueService.PLAN_TENANTS_SQL)).thenReturn(rows);
    }

    private void planOf(String schema, String plan) {
        when(jdbc.queryForList(String.format(AdminRevenueService.CURRENT_PLAN_SQL, schema), String.class))
                .thenReturn(List.of(plan));
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summary: MRR/ARR today, trend vs previous month, plan counts from tenant schemas")
    void summary_kpis() {
        payments.add(new RevenuePayment(A, 15_000, Instant.parse("2026-10-01T09:00:00Z"),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30)));
        payments.add(new RevenuePayment(B, 10_000, Instant.parse("2026-09-01T09:00:00Z"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        tenants(T1, "kv_aaaaa1", T2, "kv_aaaaa2", T3, "kv_aaaaa3", T4, "bad-schema", A.toString(), "kv_aaaaa4");
        planOf("kv_aaaaa1", "FREE");
        planOf("kv_aaaaa2", "PREMIUM_TRIAL");
        planOf("kv_aaaaa3", "PREMIUM");   // Premium without any payment (legacy / gifted)
        planOf("kv_aaaaa4", "PREMIUM");   // A: paid, counted in the MRR

        RevenueSummary s = service.getSummary(new RevenueRangeQuery(null, null));

        assertThat(s.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(s.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(s.mrr()).isEqualTo(15_000);
        assertThat(s.arr()).isEqualTo(180_000);
        assertThat(s.mrrPreviousMonth()).isEqualTo(10_000);
        assertThat(s.mrrTrendPercent()).isEqualTo(50.0);
        assertThat(s.payingTenants()).isEqualTo(1);
        assertThat(s.freeTenants()).isEqualTo(1);
        assertThat(s.trialTenants()).isEqualTo(1);
        assertThat(s.premiumTenants()).isEqualTo(2);
        assertThat(s.premiumWithoutPayment()).isEqualTo(1);
        assertThat(s.newPayingTenants()).isEqualTo(1);
        assertThat(s.revenueCollected()).isEqualTo(15_000);
        // never queries the invalid schema
        verify(jdbc, never()).queryForList(contains("bad-schema"), eq(String.class));
    }

    @Test
    @DisplayName("summary with no data: all zeros, no trend")
    void summary_noData() {
        RevenueSummary s = service.getSummary(new RevenueRangeQuery(null, null));

        assertThat(s.mrr()).isZero();
        assertThat(s.arr()).isZero();
        assertThat(s.mrrTrendPercent()).isNull();
        assertThat(s.payingTenants()).isZero();
        assertThat(s.churnRate()).isZero();
        assertThat(s.churnBase()).isZero();
        assertThat(s.conversionRate()).isZero();
    }

    @Test
    @DisplayName("plan counts: a tenant whose schema query fails is skipped")
    void planCounts_skipBrokenSchema() {
        tenants(T1, "kv_aaaaa1", T2, "kv_aaaaa2");
        when(jdbc.queryForList(String.format(AdminRevenueService.CURRENT_PLAN_SQL, "kv_aaaaa1"), String.class))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("relation does not exist")));
        planOf("kv_aaaaa2", "FREE");

        AdminRevenueService.PlanCounts counts = service.countCurrentPlans();
        assertThat(counts.free()).isEqualTo(1);
        assertThat(counts.trial()).isZero();
        assertThat(counts.premiumTenantIds()).isEmpty();
    }

    @Test
    @DisplayName("plan counts: a connection failure is rethrown (never answered as zeros)")
    void planCounts_connectionFailureRethrown() {
        tenants(T1, "kv_aaaaa1");
        when(jdbc.queryForList(String.format(AdminRevenueService.CURRENT_PLAN_SQL, "kv_aaaaa1"), String.class))
                .thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));

        assertThatThrownBy(() -> service.countCurrentPlans())
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    // ── Range validation ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "from={0} to={1}")
    @CsvSource(value = {
            "2026-02-01,2026-01-01",   // from > to
            "2026-01-01,2028-01-01",   // > 24 months
            "2026-01-01,",             // to missing
            ",2026-01-01",             // from missing
            "2026-13-01,2026-12-01",   // bad date
            "01/01/2026,2026-12-01",   // not ISO
            "1999-01-01,1999-02-01"})  // out of bounds
    @DisplayName("bad range → VALIDATION_ERROR")
    void badRange(String from, String to) {
        assertThatThrownBy(() -> service.getSummary(new RevenueRangeQuery(from, to)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("exactly 24 months is accepted")
    void range24MonthsAccepted() {
        LocalDate[] r = service.resolveRange(new RevenueRangeQuery("2025-01-01", "2026-12-31"), LocalDate.of(2026, 10, 13));
        assertThat(r).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
    }

    @ParameterizedTest(name = "month={0}")
    @ValueSource(strings = {"2026-13", "2026-1", "2026/09", "septembre", "1999-01"})
    @DisplayName("bad month → VALIDATION_ERROR")
    void badMonth(String month) {
        assertThatThrownBy(() -> service.getChurn(month))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ── Trend ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("trend: 12 calendar months ending with the current one; current month MRR is today's")
    void trend_twelveMonths() {
        payments.add(new RevenuePayment(A, 15_000, Instant.parse("2026-10-01T09:00:00Z"),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30)));
        events.add(new PlanChange(B, "PREMIUM", "FREE", "EXPIRY", Instant.parse("2026-09-12T01:00:00Z")));

        List<RevenueTrendPoint> points = service.getTrend();

        assertThat(points).hasSize(12);
        assertThat(points.get(0).month()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(points.get(11).month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(points.get(11).mrr()).isEqualTo(15_000);
        assertThat(points.get(11).newPayingTenants()).isEqualTo(1);
        assertThat(points.get(10).churnedTenants()).isEqualTo(1);
        assertThat(points.get(10).mrr()).isZero();
    }

    @Test
    @DisplayName("trend with no data: 12 empty months")
    void trend_noData() {
        assertThat(service.getTrend()).hasSize(12).allSatisfy(p -> {
            assertThat(p.mrr()).isZero();
            assertThat(p.payingTenants()).isZero();
        });
    }

    // ── Churn ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("churn: name, end date (EXPIRY → day before), tenure from the paid streak, GMV of the 30 days up to the end")
    void churn_rows() throws SQLException {
        payments.add(new RevenuePayment(A, 15_000, Instant.parse("2026-01-01T09:00:00Z"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 11)));
        payments.add(new RevenuePayment(B, 15_000, Instant.parse("2026-08-20T09:00:00Z"),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 30)));
        events.add(new PlanChange(A, "PREMIUM", "FREE", "EXPIRY", Instant.parse("2026-09-12T01:00:00Z")));
        stubTenantLookup(A, null, "kv_aaaaa1");
        // window = the 30 WAT days ending on the plan end date (11/09): [13/08 00:00, 12/09 00:00) WAT
        when(jdbc.queryForObject(eq(String.format(AdminRevenueService.GMV_SQL, "kv_aaaaa1")), eq(Long.class),
                eq(java.sql.Timestamp.from(Instant.parse("2026-08-12T23:00:00Z"))),
                eq(java.sql.Timestamp.from(Instant.parse("2026-09-11T23:00:00Z")))))
                .thenReturn(42_000L);

        ChurnReport report = service.getChurn("2026-09");

        assertThat(report.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(report.payingAtMonthStart()).isEqualTo(2);
        assertThat(report.churnRate()).isEqualTo(0.5);
        assertThat(report.tenants()).singleElement().satisfies(t -> {
            assertThat(t.tenantName()).isNull();
            assertThat(t.planEndDate()).isEqualTo(LocalDate.of(2026, 9, 11));
            assertThat(t.tenureMonths()).isEqualTo(8.3);
            assertThat(t.lastGmv30d()).isEqualTo(42_000L);
        });
    }

    @Test
    @DisplayName("churn: unreadable schema → GMV 0, row kept")
    void churn_brokenSchema_gmvZero() throws SQLException {
        events.add(new PlanChange(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", Instant.parse("2026-09-12T10:00:00Z")));
        stubTenantLookup(A, "Boutique", "kv_aaaaa1");
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("no table")));

        ChurnReport report = service.getChurn("2026-09");

        assertThat(report.tenants()).singleElement().satisfies(t -> {
            assertThat(t.tenantName()).isEqualTo("Boutique");
            assertThat(t.lastGmv30d()).isZero();
            assertThat(t.tenureMonths()).isNull();
        });
        // nobody paying before the month, but the churned tenant is part of the base (rate ≤ 100 %)
        assertThat(report.payingAtMonthStart()).isEqualTo(1);
        assertThat(report.churnRate()).isEqualTo(1.0);
        assertThat(report.tenants().get(0).planEndDate()).isEqualTo(LocalDate.of(2026, 9, 12));
    }

    @Test
    @DisplayName("churn: empty month → no rows, rate 0")
    void churn_empty() {
        ChurnReport report = service.getChurn(null);
        assertThat(report.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(report.tenants()).isEmpty();
        assertThat(report.churnRate()).isZero();
    }

    private void stubTenantLookup(UUID id, String name, String schema) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id.toString());
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("schema_name")).thenReturn(schema);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(startsWith("SELECT id, name, schema_name FROM public.tenants WHERE id IN"),
                any(RowCallbackHandler.class), any(Object[].class));
    }
}
