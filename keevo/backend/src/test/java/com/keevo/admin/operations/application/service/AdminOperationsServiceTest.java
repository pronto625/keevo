package com.keevo.admin.operations.application.service;

import com.keevo.admin.operations.domain.model.*;
import com.keevo.admin.operations.domain.port.in.OperationsPeriodQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOperationsService")
class AdminOperationsServiceTest {

    /** 2026-10-13 11:00 in Douala (WAT = UTC+1). */
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    @Mock JdbcTemplate jdbc;

    AdminOperationsService service;

    @BeforeEach
    void setUp() {
        service = new AdminOperationsService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Period resolution ─────────────────────────────────────────────────────

    @Test
    @DisplayName("7D covers the 7 WAT days ending today, as a half-open instant window")
    void window7D_usesWatDayBoundaries() {
        AdminOperationsService.Window w = service.resolveWindow(new OperationsPeriodQuery("7D", null, null));

        assertThat(w.firstDay()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(w.lastDay()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(w.start()).isEqualTo(Instant.parse("2026-10-06T23:00:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-10-13T23:00:00Z"));
        assertThat(w.label()).isEqualTo("7 derniers jours");
    }

    @Test
    @DisplayName("TODAY uses the WAT calendar day even when UTC is still the previous day")
    void windowToday_usesWatCalendarDay() {
        service = new AdminOperationsService(jdbc,
                Clock.fixed(Instant.parse("2026-10-12T23:30:00Z"), ZoneOffset.UTC)); // 00:30 WAT on the 13th

        AdminOperationsService.Window w = service.resolveWindow(new OperationsPeriodQuery("TODAY", null, null));

        assertThat(w.firstDay()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(w.lastDay()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(w.label()).isEqualTo("Aujourd'hui");
    }

    @ParameterizedTest(name = "{0} → {1} days")
    @CsvSource({"30D,30", "90D,90", "today,1"})
    @DisplayName("fixed periods cover the expected number of days")
    void fixedPeriods_dayCount(String period, long days) {
        AdminOperationsService.Window w = service.resolveWindow(new OperationsPeriodQuery(period, null, null));
        assertThat(ChronoUnit.DAYS.between(w.firstDay(), w.lastDay()) + 1).isEqualTo(days);
    }

    @Test
    @DisplayName("missing period defaults to 7D")
    void missingPeriod_defaultsTo7D() {
        AdminOperationsService.Window w = service.resolveWindow(new OperationsPeriodQuery(null, null, null));
        assertThat(w.firstDay()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    @DisplayName("CUSTOM accepts inclusive from/to and builds a French label")
    void custom_valid() {
        AdminOperationsService.Window w = service.resolveWindow(
                new OperationsPeriodQuery("CUSTOM", "2026-01-01", "2026-01-31"));

        assertThat(w.start()).isEqualTo(Instant.parse("2025-12-31T23:00:00Z"));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-01-31T23:00:00Z"));
        assertThat(w.label()).isEqualTo("Du 01/01/2026 au 31/01/2026");
    }

    @Test
    @DisplayName("CUSTOM of exactly 366 days is accepted")
    void custom_366DaysAccepted() {
        AdminOperationsService.Window w = service.resolveWindow(
                new OperationsPeriodQuery("CUSTOM", "2024-01-01", "2024-12-31")); // leap year: 366 days
        assertThat(ChronoUnit.DAYS.between(w.firstDay(), w.lastDay()) + 1).isEqualTo(366);
    }

    @ParameterizedTest(name = "period={0} from={1} to={2}")
    @CsvSource(value = {
            "CUSTOM,2026-02-01,2026-01-01",   // from > to
            "CUSTOM,2024-01-01,2025-01-01",   // 367 days
            "CUSTOM,,2026-01-01",             // missing from
            "CUSTOM,2026-01-01,",             // missing to
            "CUSTOM,01/01/2026,2026-01-31",   // not ISO
            "1Y,,",                           // unknown period
            "CUSTOM,1999-12-31,2000-01-02",   // year < 2000
            "CUSTOM,+10000-01-01,+10000-01-02", // year > 9999
    })
    @DisplayName("invalid periods → VALIDATION_ERROR")
    void invalidPeriods_throwValidationError(String period, String from, String to) {
        assertThatThrownBy(() -> service.getOverview(new OperationsPeriodQuery(period, from, to)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(jdbc);
    }

    // ── getOverview ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("overview sums GMV/orders across tenants, counts active and new tenants")
    void overview_aggregatesAcrossTenants() {
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", Instant.parse("2026-10-10T08:00:00Z")),
                tenant("t2", "Boutique B", "kv_bbbbbb", "PREMIUM", Instant.parse("2026-01-01T08:00:00Z")),
                tenant("t3", "Boutique C", "kv_cccccc", "FREE", Instant.parse("2026-10-06T22:59:59Z")));
        stubRows("kv_aaaaaa.sales", row("gmv", 1000L, "order_count", 1L));
        stubRows("kv_bbbbbb.sales", row("gmv", 500L, "order_count", 1L));
        stubRows("kv_cccccc.sales", row("gmv", 0L, "order_count", 0L));

        OperationsOverview result = service.getOverview(new OperationsPeriodQuery("7D", null, null));

        assertThat(result.totalGmv()).isEqualTo(1500L);
        assertThat(result.totalOrders()).isEqualTo(2L);
        assertThat(result.activeTenantsCount()).isEqualTo(2);
        // t1 created inside the window; t3 one second before its start (WAT midnight of the 7th).
        assertThat(result.newTenantsCount()).isEqualTo(1);
        assertThat(result.periodLabel()).isEqualTo("7 derniers jours");
    }

    @Test
    @DisplayName("overview counts COMPLETED sales only, bound to the window instants")
    void overview_queryFiltersCompletedSalesInWindow() {
        stubTenants(tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null));
        stubRows("kv_aaaaaa.sales", row("gmv", 0L, "order_count", 0L));

        service.getOverview(new OperationsPeriodQuery("7D", null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).queryForList(sql.capture(), args.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("FROM kv_aaaaaa.sales")
                .contains("status = 'COMPLETED'")
                .contains("COALESCE(occurred_at, created_at) >= ?")
                .contains("COALESCE(occurred_at, created_at) < ?");
        assertThat(args.getAllValues()).containsExactly(
                OffsetDateTime.parse("2026-10-06T23:00:00Z"),
                OffsetDateTime.parse("2026-10-13T23:00:00Z"));
    }

    @Test
    @DisplayName("overview skips a tenant whose schema query fails and keeps the others")
    void overview_skipsBrokenTenant() {
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "Boutique B", "kv_bbbbbb", "FREE", null));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("relation does not exist")));
        stubRows("kv_bbbbbb.sales", row("gmv", 700L, "order_count", 2L));

        OperationsOverview result = service.getOverview(new OperationsPeriodQuery("30D", null, null));

        assertThat(result.totalGmv()).isEqualTo(700L);
        assertThat(result.totalOrders()).isEqualTo(2L);
        assertThat(result.activeTenantsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a skipped tenant created in the period is not counted as new")
    void overview_skippedTenantNotCountedAsNew() {
        Instant inWindow = Instant.parse("2026-10-10T08:00:00Z");
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", inWindow),
                tenant("t2", "Boutique B", "kv_bbbbbb", "FREE", inWindow));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("relation does not exist")));
        stubRows("kv_bbbbbb.sales", row("gmv", 0L, "order_count", 0L));

        OperationsOverview result = service.getOverview(new OperationsPeriodQuery("7D", null, null));

        assertThat(result.newTenantsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a per-tenant data error (integrity / data) skips only that tenant")
    void overview_skipsTenantOnDataError() {
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "Boutique B", "kv_bbbbbb", "FREE", null));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("bad data"));
        stubRows("kv_bbbbbb.sales", row("gmv", 700L, "order_count", 2L));

        assertThat(service.getOverview(new OperationsPeriodQuery("7D", null, null)).totalGmv()).isEqualTo(700L);
    }

    static Stream<Arguments> resourceFailures() {
        return Stream.of(
                Arguments.of(new DataAccessResourceFailureException("db down")),
                Arguments.of(new CannotGetJdbcConnectionException("pool exhausted")),
                Arguments.of(new QueryTimeoutException("timeout")),               // TransientDataAccessException
                Arguments.of(new TransientDataAccessResourceException("transient")),
                Arguments.of(new RecoverableDataAccessException("recoverable")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceFailures")
    @DisplayName("connection / resource failures are rethrown (endpoint fails, no all-zero 200)")
    void resourceFailures_areRethrown(RuntimeException failure) {
        stubTenants(tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class))).thenThrow(failure);

        assertThatThrownBy(() -> service.getOverview(new OperationsPeriodQuery("7D", null, null))).isSameAs(failure);
        assertThatThrownBy(() -> service.getTrend(new OperationsPeriodQuery("7D", null, null))).isSameAs(failure);
        assertThatThrownBy(() -> service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 0, 25))
                .isSameAs(failure);
        assertThatThrownBy(() -> service.listInactiveTenants()).isSameAs(failure);
    }

    @Test
    @DisplayName("tenant activity counts only active STORE-type stores (warehouses excluded)")
    void tenantActivity_storeCountExcludesWarehouses() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", "FREE", null));
        stubRows("kv_aaaaaa.sales", activityRow(1L, 1L, null, null, 1L, "FREE"));

        service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 0, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("kv_aaaaaa.stores WHERE is_active = true AND type = 'STORE'");
    }

    @Test
    @DisplayName("tenants with an invalid schema name are never queried")
    void overview_invalidSchemaNeverInterpolated() {
        stubTenants(
                tenant("t1", "Evil", "kv_a; DROP TABLE x", "FREE", null),
                tenant("t2", "No schema", null, "FREE", null));

        OperationsOverview result = service.getOverview(new OperationsPeriodQuery("7D", null, null));

        assertThat(result.totalGmv()).isZero();
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("tenant list query excludes the KV-ADMIN system tenant and non-ACTIVE tenants")
    void tenantQuery_excludesSystemAndInactiveStatus() {
        stubTenants();

        service.getOverview(new OperationsPeriodQuery("7D", null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("00000000-0000-0000-0000-000000000000")
                .contains("status = 'ACTIVE'");
    }

    // ── getTrend ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("trend returns one point per WAT day, zero-filled, summed across tenants")
    void trend_zeroFillsMissingDays() {
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "Boutique B", "kv_bbbbbb", "FREE", null));
        stubRows("kv_aaaaaa.sales",
                row("day", java.sql.Date.valueOf("2026-10-08"), "gmv", 1000L, "order_count", 2L),
                row("day", java.sql.Date.valueOf("2026-10-13"), "gmv", 300L, "order_count", 1L));
        stubRows("kv_bbbbbb.sales",
                row("day", java.sql.Date.valueOf("2026-10-08"), "gmv", 500L, "order_count", 1L));

        List<GmvTrendPoint> points = service.getTrend(new OperationsPeriodQuery("7D", null, null));

        assertThat(points).hasSize(7);
        assertThat(points).extracting(GmvTrendPoint::date)
                .containsExactly(
                        LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9),
                        LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12),
                        LocalDate.of(2026, 10, 13));
        assertThat(points).extracting(GmvTrendPoint::gmv).containsExactly(0L, 1500L, 0L, 0L, 0L, 0L, 300L);
        assertThat(points).extracting(GmvTrendPoint::orderCount).containsExactly(0L, 3L, 0L, 0L, 0L, 0L, 1L);
    }

    @Test
    @DisplayName("trend groups by the WAT calendar day of the sale time")
    void trend_groupsByWatDay() {
        stubTenants(tenant("t1", "Boutique A", "kv_aaaaaa", "FREE", null));
        stubRows("kv_aaaaaa.sales");

        service.getTrend(new OperationsPeriodQuery("TODAY", null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("AT TIME ZONE 'Africa/Douala'")
                .contains("status = 'COMPLETED'")
                .contains("GROUP BY 1");
    }

    // ── listTenantActivity ────────────────────────────────────────────────────

    @Test
    @DisplayName("tenant activity is sorted by GMV desc then name, with plan from subscriptions")
    void tenantActivity_sortedAndMapped() {
        Timestamp lastSale = Timestamp.from(Instant.parse("2026-10-12T09:00:00Z"));
        Timestamp lastAudit = Timestamp.from(Instant.parse("2026-10-12T10:00:00Z"));
        stubTenants(
                tenant("t1", "Zeta", "kv_aaaaaa", "FREE", null),
                tenant("t2", "alpha", "kv_bbbbbb", "FREE", null),
                tenant("t3", null, "kv_cccccc", "PREMIUM", null),
                tenant("t4", "Beta", "kv_dddddd", "FREE", null));
        stubRows("kv_aaaaaa.sales", activityRow(500L, 2L, lastSale, lastAudit, 2L, "PREMIUM_TRIAL"));
        stubRows("kv_bbbbbb.sales", activityRow(500L, 1L, null, null, 1L, null));
        stubRows("kv_cccccc.sales", activityRow(9000L, 5L, lastSale, null, 3L, "PREMIUM"));
        stubRows("kv_dddddd.sales", activityRow(0L, 0L, null, lastAudit, 1L, "FREE"));

        TenantActivityPage page = service.listTenantActivity(new OperationsPeriodQuery("30D", null, null), 0, 25);

        assertThat(page.content()).extracting(TenantActivity::tenantId).containsExactly("t3", "t2", "t1", "t4");
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.number()).isZero();

        TenantActivity zeta = page.content().get(2);
        assertThat(zeta.plan()).isEqualTo("PREMIUM_TRIAL");
        assertThat(zeta.gmv()).isEqualTo(500L);
        assertThat(zeta.orderCount()).isEqualTo(2L);
        assertThat(zeta.storeCount()).isEqualTo(2);
        assertThat(zeta.lastActivityAt()).isEqualTo(lastAudit.toInstant()); // MAX(sale, audit)

        TenantActivity alpha = page.content().get(1);
        assertThat(alpha.plan()).isEqualTo("FREE"); // no subscription row → public.tenants.plan_type
        assertThat(alpha.lastActivityAt()).isNull();
    }

    @Test
    @DisplayName("tenant activity paginates 25 rows per page by default")
    void tenantActivity_paginates() {
        List<Map<String, Object>> tenants = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String schema = String.format("kv_t%05d", i);
            tenants.add(tenant("id" + i, String.format("T%02d", i), schema, "FREE", null));
            stubRows(schema + ".sales", activityRow(1000L - i, 1L, null, null, 1L, "FREE"));
        }
        when(jdbc.queryForList(contains("FROM public.tenants"))).thenReturn(tenants);

        TenantActivityPage page1 = service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 1, null);

        assertThat(page1.content()).hasSize(5);
        assertThat(page1.content().get(0).tenantName()).isEqualTo("T25");
        assertThat(page1.totalElements()).isEqualTo(30);
        assertThat(page1.totalPages()).isEqualTo(2);
        assertThat(page1.number()).isEqualTo(1);
    }

    @Test
    @DisplayName("a page beyond the last one is empty, not an error")
    void tenantActivity_pageBeyondEnd() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", "FREE", null));
        stubRows("kv_aaaaaa.sales", activityRow(1L, 1L, null, null, 1L, "FREE"));

        TenantActivityPage page = service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 5, 25);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.number()).isEqualTo(5);
    }

    @ParameterizedTest(name = "page={0} pageSize={1}")
    @CsvSource({"-1,25", "0,0", "0,101"})
    @DisplayName("invalid page / pageSize → VALIDATION_ERROR")
    void tenantActivity_invalidPaging(int page, int pageSize) {
        assertThatThrownBy(() -> service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), page, pageSize))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("tenant activity skips a tenant whose schema query fails")
    void tenantActivity_skipsBrokenTenant() {
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "B", "kv_bbbbbb", "FREE", null));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("relation does not exist")));
        stubRows("kv_bbbbbb.sales", activityRow(10L, 1L, null, null, 1L, "FREE"));

        TenantActivityPage page = service.listTenantActivity(new OperationsPeriodQuery("7D", null, null), 0, 25);

        assertThat(page.content()).extracting(TenantActivity::tenantId).containsExactly("t2");
    }

    // ── listInactiveTenants ───────────────────────────────────────────────────

    @Test
    @DisplayName("inactive: last activity = MAX(sale, audit); > 14 days or never → listed")
    void inactive_thresholdAndDays() {
        stubTenants(
                tenant("t1", "Dormant", "kv_aaaaaa", "FREE", null),
                tenant("t2", "Never", "kv_bbbbbb", "FREE", null),
                tenant("t3", "Active", "kv_cccccc", "FREE", null),
                tenant("t4", "Borderline", "kv_dddddd", "FREE", null));
        stubRows("kv_aaaaaa.sales", inactiveRow(daysAgo(20), daysAgo(15), "PREMIUM"));
        stubRows("kv_bbbbbb.sales", inactiveRow(null, null, null));
        stubRows("kv_cccccc.sales", inactiveRow(daysAgo(30), daysAgo(2), "FREE"));
        stubRows("kv_dddddd.sales", inactiveRow(daysAgo(14), null, "FREE")); // exactly 14 days → still active

        List<InactiveTenant> result = service.listInactiveTenants();

        assertThat(result).extracting(InactiveTenant::tenantId).containsExactly("t2", "t1");
        InactiveTenant never = result.get(0);
        assertThat(never.lastActivityAt()).isNull();
        assertThat(never.daysSinceLastActivity()).isNull();
        assertThat(never.plan()).isEqualTo("FREE");
        InactiveTenant dormant = result.get(1);
        assertThat(dormant.lastActivityAt()).isEqualTo(daysAgo(15).toInstant());
        assertThat(dormant.daysSinceLastActivity()).isEqualTo(15L);
        assertThat(dormant.plan()).isEqualTo("PREMIUM");
    }

    @Test
    @DisplayName("inactive: sorted never-active first, then longest dormant")
    void inactive_sorted() {
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "B", "kv_bbbbbb", "FREE", null),
                tenant("t3", "C", "kv_cccccc", "FREE", null));
        stubRows("kv_aaaaaa.sales", inactiveRow(daysAgo(20), null, "FREE"));
        stubRows("kv_bbbbbb.sales", inactiveRow(daysAgo(60), null, "FREE"));
        stubRows("kv_cccccc.sales", inactiveRow(null, null, "FREE"));

        assertThat(service.listInactiveTenants()).extracting(InactiveTenant::tenantId)
                .containsExactly("t3", "t2", "t1");
    }

    @Test
    @DisplayName("inactive: a tenant whose schema query fails is skipped")
    void inactive_skipsBrokenTenant() {
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", "FREE", null),
                tenant("t2", "B", "kv_bbbbbb", "FREE", null));
        when(jdbc.queryForList(contains("kv_aaaaaa.sales"), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("relation does not exist")));
        stubRows("kv_bbbbbb.sales", inactiveRow(null, null, "FREE"));

        assertThat(service.listInactiveTenants()).extracting(InactiveTenant::tenantId).containsExactly("t2");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(NOW.minus(days, ChronoUnit.DAYS));
    }

    @SafeVarargs
    private void stubTenants(Map<String, Object>... tenants) {
        when(jdbc.queryForList(contains("FROM public.tenants"))).thenReturn(List.of(tenants));
    }

    @SafeVarargs
    private void stubRows(String sqlFragment, Map<String, Object>... rows) {
        lenient().when(jdbc.queryForList(contains(sqlFragment), any(Object[].class))).thenReturn(List.of(rows));
    }

    private static Map<String, Object> tenant(String id, String name, String schema, String plan, Instant createdAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("schema_name", schema);
        m.put("plan_type", plan);
        m.put("created_at", createdAt == null ? null : Timestamp.from(createdAt));
        return m;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> activityRow(long gmv, long orders, Timestamp lastSale, Timestamp lastAudit,
                                                   long stores, String plan) {
        return row("gmv", gmv, "order_count", orders, "last_activity_at", greatest(lastSale, lastAudit),
                "store_count", stores, "plan", plan);
    }

    private static Map<String, Object> inactiveRow(Timestamp lastSale, Timestamp lastAudit, String plan) {
        return row("last_activity_at", greatest(lastSale, lastAudit), "plan", plan);
    }

    /** What SQL GREATEST returns (NULLs ignored) — the service reads one last_activity_at column. */
    private static Timestamp greatest(Timestamp a, Timestamp b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.after(b) ? a : b;
    }
}
