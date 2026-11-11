package com.keevo.admin.analytics.application.service;

import com.keevo.admin.analytics.domain.model.*;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Bucket;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Totals;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests of {@link AdminAnalyticsService}. Story 19.11b: the per-tenant figures go through {@link CrossTenantSql}
 * (mocked here): the branch SQL / bound values are checked by applying the captured branch writer to a tenant, the
 * Java merge by feeding chunk rows; the SQL itself runs on PostgreSQL in {@code AdminAnalyticsServiceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAnalyticsService (9-4)")
class AdminAnalyticsServiceTest {

    /** Tuesday 2026-10-13 11:00 WAT (UTC+1). Current ISO week starts Monday 2026-10-12. */
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final TenantBranch FULL = new TenantBranch("11111111-1111-1111-1111-111111111111", "A",
            "kv_aaaaaa", "ACTIVE", null, EnumSet.allOf(TenantTable.class));

    @Mock JdbcTemplate jdbc;
    @Mock OwnerRecipientResolver ownerRecipientResolver;
    @Mock PlatformRevenuePort revenue;
    @Mock CrossTenantSql crossTenant;

    AdminAnalyticsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdminAnalyticsService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), ownerRecipientResolver, revenue,
                crossTenant);
        lenient().when(revenue.totals(any(), any(), any(), any())).thenReturn(Map.of());
        // Every given tenant holds every table unless a test says otherwise.
        lenient().when(crossTenant.withPresence(anyList())).thenAnswer(inv -> ((List<TenantBranch>) inv.getArgument(0))
                .stream().map(t -> t.withTables(EnumSet.allOf(TenantTable.class))).toList());
        lenient().doReturn(List.of()).when(crossTenant).query(anyList(), any(), any(), any());
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DAU 3 / MAU 10 → ratio 0.3; platform totals summed across chunks")
    void overview_dauMauAndTotals() {
        stubDistinctUsers(TODAY, TODAY, 3L);
        stubDistinctUsers(TODAY.minusDays(29), TODAY, 10L);
        stubTenants(
                tenant("t1", "Boutique A", "kv_aaaaaa", Instant.parse("2026-01-01T08:00:00Z")),
                tenant("t2", "Boutique B", "kv_bbbbbb", Instant.parse("2026-02-01T08:00:00Z")));
        doReturn(List.of(new long[]{2, 1, 40}, new long[]{1, 1, 10})).when(crossTenant).query(anyList(), any(), any(), any());
        stubRevenue(Bucket.TENANT, Map.of("t1", new Totals(10_000L, 5L), "t2", new Totals(2_500L, 2L)));

        AnalyticsOverview o = service.getOverview();

        assertThat(o.dau()).isEqualTo(3);
        assertThat(o.mau()).isEqualTo(10);
        assertThat(o.dauMauRatio()).isEqualTo(0.3);
        assertThat(o.totalTenants()).isEqualTo(2);
        assertThat(o.totalStores()).isEqualTo(3);
        assertThat(o.activeStores()).isEqualTo(2);
        assertThat(o.totalProducts()).isEqualTo(50);
        assertThat(o.totalCompletedSales()).isEqualTo(7);
        assertThat(o.totalGmv()).isEqualTo(12_500);
    }

    @Test
    @DisplayName("MAU 0 → ratio null (« — »)")
    void overview_mauZero_ratioNull() {
        stubTenants();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

        assertThat(service.getOverview().dauMauRatio()).isNull();
    }

    @Test
    @DisplayName("overview SQL: STORE type only, non-archived products, active stores since WAT D-29; no GMV in SQL")
    void overview_sqlPredicates() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getOverview();

        Branch branch = branch(FULL);
        assertThat(branch.sql())
                .contains("kv_aaaaaa.stores WHERE is_active = true AND type = 'STORE'")
                .contains("kv_aaaaaa.products WHERE archived = false")
                .contains("sa.status = 'COMPLETED'")
                .contains("COALESCE(sa.occurred_at, sa.created_at) < ?")
                .doesNotContain("SUM(total_amount)"); // 20.13: GMV comes from the revenue port
        // Active stores: [WAT D-29, start of tomorrow) — future-dated sales never count.
        assertThat(branch.params()).containsExactly(
                OffsetDateTime.parse("2026-09-13T23:00:00Z"), OffsetDateTime.parse("2026-10-13T23:00:00Z"));
        assertThat(outer("U")).isEqualTo("SELECT COALESCE(SUM(store_count), 0), COALESCE(SUM(active_store_count), 0), "
                + "COALESCE(SUM(product_count), 0) FROM (U) u");
    }

    @Test
    @DisplayName("19.11b: a table absent from a schema reads 0 instead of failing the statement")
    void overview_missingTablesReadZero() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getOverview();

        Branch branch = branch(new TenantBranch(FULL.id(), "A", "kv_aaaaaa", "ACTIVE", null,
                EnumSet.of(TenantTable.PRODUCTS_ARCHIVED)));
        assertThat(branch.sql()).doesNotContain(".stores").doesNotContain(".sales")
                .contains("CAST(0 AS bigint) AS store_count").contains("kv_aaaaaa.products");
        assertThat(branch.params()).isEmpty();
    }

    @Test
    @DisplayName("tenant list excludes KV-ADMIN; current KPIs exclude DELETED tenants (never queried)")
    void tenantQuery_excludesSystemAndDeleted() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null), deleted(tenant("t2", "Gone", "kv_bbbbbb", null)));
        doReturn(List.<long[]>of(new long[]{1, 0, 0})).when(crossTenant).query(anyList(), any(), any(), any());

        AnalyticsOverview o = service.getOverview();

        assertThat(o.totalTenants()).isEqualTo(1);
        assertThat(o.totalStores()).isEqualTo(1);
        verify(jdbc).queryForList(argThat((String s) -> s.contains("FROM public.tenants")
                && s.contains("'00000000-0000-0000-0000-000000000000'::uuid")));
        assertThat(queriedTenants()).containsExactly("t1");
    }

    @Test
    @DisplayName("DAU/MAU read only activity of platform tenants: KV-ADMIN and DELETED excluded")
    void dauMau_platformFilter() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants();

        service.getOverview();

        verify(jdbc, times(2)).queryForObject(argThat((String s) -> s.contains("COUNT(DISTINCT a.user_id)")
                        && s.contains("JOIN public.tenants t ON t.id = a.tenant_id")
                        && s.contains("t.id <> '00000000-0000-0000-0000-000000000000'::uuid")
                        && s.contains("t.status <> 'DELETED'")),
                eq(Long.class), any(Object[].class));
    }

    @Test
    @DisplayName("19.11b: the revenue port reads every current tenant once, with its resolved table presence")
    void overview_revenueReadsPresentTenants() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null), tenant("t2", "B", "kv_bbbbbb", null),
                tenant("t3", "C", "kv_cccccc", null));
        stubRevenue(Bucket.TENANT, Map.of("t1", new Totals(5_000L, 1L), "t2", new Totals(700L, 2L),
                "t3", new Totals(9_000L, 3L)));

        AnalyticsOverview o = service.getOverview();

        assertThat(o.totalGmv()).isEqualTo(14_700L);
        assertThat(o.totalTenants()).isEqualTo(3);
        verify(crossTenant, times(1)).withPresence(anyList());
        verify(revenue).totals(argThat(l -> l.size() == 3 && l.stream().allMatch(TenantBranch::resolved)),
                isNull(), isNull(), eq(Bucket.TENANT));
    }

    static Stream<Arguments> resourceFailures() {
        return Stream.of(
                Arguments.of(new DataAccessResourceFailureException("db down")),
                Arguments.of(new CannotGetJdbcConnectionException("pool exhausted")),
                Arguments.of(new QueryTimeoutException("timeout")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceFailures")
    @DisplayName("connection / resource failures are rethrown (no all-zero 200)")
    void resourceFailures_rethrown(RuntimeException failure) {
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        lenient().when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class))).thenReturn(List.of());
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));
        lenient().doThrow(failure).when(crossTenant).query(anyList(), any(), any(), any());
        lenient().when(ownerRecipientResolver.resolveOwnerWhatsAppPhones(anyCollection())).thenThrow(failure);
        lenient().when(revenue.totals(any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.getOverview()).isSameAs(failure);
        assertThatThrownBy(() -> service.getGrowth(null)).isSameAs(failure);
        assertThatThrownBy(() -> service.getTopStores(null, null)).isSameAs(failure);
        assertThatThrownBy(() -> service.getGeography()).isSameAs(failure);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceFailures")
    @DisplayName("retention / adoption: connection / resource failures are rethrown")
    void resourceFailures_rethrown_retentionAdoption(RuntimeException failure) {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));
        // Adoption: t1 is active, its chunk fails on a resource error.
        lenient().when(jdbc.queryForList(contains("tenant_id::text AS tenant_id FROM public.user_daily_activity"), any(Object[].class)))
                .thenReturn(List.of(row("tenant_id", "t1")));
        lenient().doThrow(failure).when(crossTenant).query(anyList(), any(), any(), any());
        // Retention: the activity read fails.
        lenient().when(jdbc.queryForList(contains("ISODOW"), any(Object[].class))).thenThrow(failure);

        assertThatThrownBy(() -> service.getRetention(null)).isSameAs(failure);
        assertThatThrownBy(() -> service.getAdoption()).isSameAs(failure);
    }

    @Test
    @DisplayName("tenants with an invalid schema name are never interpolated")
    void invalidSchema_neverQueried() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        stubTenants(tenant("t1", "Evil", "kv_a; DROP TABLE x", null), tenant("t2", "None", null, null));

        AnalyticsOverview o = service.getOverview();

        assertThat(o.totalTenants()).isEqualTo(2);
        verify(crossTenant).withPresence(List.of());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
        verifyNoInteractions(ownerRecipientResolver);
    }

    // ── Growth ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("growth: one point per month, new + cumulative tenants, sales/GMV summed, MAU per month")
    void growth_months() {
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", Instant.parse("2025-06-01T08:00:00Z")), // before the window
                tenant("t2", "B", "kv_bbbbbb", Instant.parse("2026-09-30T23:30:00Z")), // 1 Oct 00:30 WAT
                tenant("t3", "C", "kv_cccccc", Instant.parse("2026-09-10T08:00:00Z")),
                deleted(tenant("t4", "Gone", "kv_dddddd", Instant.parse("2026-09-15T08:00:00Z"))));
        stubRevenue(Bucket.MONTH, Map.of("2026-09", new Totals(1000L, 2L), "2026-10", new Totals(800L, 4L),
                "2026-07", new Totals(99L, 1L)));
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class)))
                .thenReturn(List.of(row("month", "2026-10", "mau", 7L)));

        List<GrowthPoint> points = service.getGrowth("3");

        assertThat(points).extracting(GrowthPoint::month).containsExactly("2026-08", "2026-09", "2026-10");
        GrowthPoint aug = points.get(0);
        GrowthPoint sep = points.get(1);
        GrowthPoint oct = points.get(2);
        assertThat(aug.newTenants()).isZero();
        assertThat(aug.cumulativeTenants()).isEqualTo(1);
        // History counts the DELETED tenant too (created in September): past figures never shrink.
        assertThat(sep.newTenants()).isEqualTo(2);
        assertThat(sep.cumulativeTenants()).isEqualTo(3);
        assertThat(sep.salesCount()).isEqualTo(2);
        assertThat(sep.gmv()).isEqualTo(1000);
        assertThat(oct.newTenants()).isEqualTo(1); // WAT month, not UTC
        assertThat(oct.cumulativeTenants()).isEqualTo(4);
        assertThat(oct.salesCount()).isEqualTo(4);
        assertThat(oct.gmv()).isEqualTo(800);
        assertThat(oct.mau()).isEqualTo(7);
        assertThat(sep.mau()).isZero();
        verify(jdbc, never()).queryForList(contains("kv_dddddd"), any(Object[].class));
        // 20.13: revenue read once for the live tenants (DELETED excluded), over the WAT window of the 3 months.
        verify(revenue).totals(argThat(l -> l.stream().map(TenantBranch::id).toList().equals(List.of("t1", "t2", "t3"))),
                eq(Instant.parse("2026-07-31T23:00:00Z")), eq(Instant.parse("2026-10-31T23:00:00Z")), eq(Bucket.MONTH));
        // Past months' MAU: KV-ADMIN excluded, DELETED tenants' past activity kept.
        verify(jdbc).queryForList(argThat((String s) -> s.contains("COUNT(DISTINCT a.user_id) AS mau")
                        && s.contains("t.id <> '00000000-0000-0000-0000-000000000000'::uuid")
                        && !s.contains("DELETED")),
                any(Object[].class));
    }

    @Test
    @DisplayName("growth defaults to 12 months")
    void growth_default12() {
        stubTenants();
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class))).thenReturn(List.of());

        List<GrowthPoint> points = service.getGrowth(null);

        assertThat(points).hasSize(12);
        assertThat(points.get(0).month()).isEqualTo("2025-11");
        assertThat(points.get(11).month()).isEqualTo("2026-10");
    }

    @ParameterizedTest
    @CsvSource({"0", "25", "-1", "abc", "1.5"})
    @DisplayName("growth months out of 1..24 or not an integer → VALIDATION_ERROR")
    void growth_invalidMonths(String months) {
        assertValidationError(() -> service.getGrowth(months));
        verifyNoInteractions(jdbc);
    }

    // ── Geography ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("geography: calling code → country, +44 → OTHER, no phone → UNKNOWN, sorted by count; one batch lookup")
    void geography_callingCodes() {
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", null), tenant("t2", "B", "kv_bbbbbb", null),
                tenant("t3", "C", "kv_cccccc", null), tenant("t4", "D", "kv_dddddd", null),
                tenant("t5", "E", "kv_eeeeee", null), tenant("t6", "F", "kv_ffffff", null));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhones(anyCollection())).thenReturn(Map.of(
                "kv_aaaaaa", "+237690000001", "kv_bbbbbb", "+237670000002", "kv_cccccc", "+2250700000000",
                "kv_dddddd", "+447700900000"));

        List<CountryCount> result = service.getGeography();

        assertThat(result).containsExactly(
                new CountryCount("CM", 2), new CountryCount("UNKNOWN", 2), new CountryCount("CI", 1),
                new CountryCount("OTHER", 1));
        verify(ownerRecipientResolver).resolveOwnerWhatsAppPhones(
                List.of("kv_aaaaaa", "kv_bbbbbb", "kv_cccccc", "kv_dddddd", "kv_eeeeee", "kv_ffffff"));
        verify(ownerRecipientResolver, never()).resolveOwnerWhatsAppPhone(anyString());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "+237690000000,CM", "+2250700000000,CI", "+221770000000,SN", "+241060000000,GA",
            "+242060000000,CG", "+243810000000,CD", "+22990000000,BJ", "+22890000000,TG",
            "+22670000000,BF", "+22370000000,ML", "+22790000000,NE", "+23560000000,TD",
            "+23670000000,CF", "+240222000000,GQ", "+33612345678,FR", "+447700900000,OTHER",
            "00237690000000,CM", "237690000000,CM", "2250700000000,CI", "33612345678,FR",
            "+237 690 00 00 00,CM", "44770090000,OTHER", "2376900,OTHER", "690000000,OTHER",
            "NULL,UNKNOWN", "'',UNKNOWN"}, nullValues = "NULL")
    @DisplayName("calling code mapping")
    void countryOf(String phone, String expected) {
        assertThat(AdminAnalyticsService.countryOf(phone)).isEqualTo(expected);
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cohort of 4 tenants: 3 active in W+1, 1 in W+2 → 75 %, 25 %; in-progress / future weeks null")
    void retention_matrixRow() {
        LocalDate w = LocalDate.of(2026, 9, 21); // three weeks before the current one: W+1, W+2 complete
        stubTenants(
                tenant("t1", "A", "kv_aaaaaa", Instant.parse("2026-09-21T08:00:00Z")),
                tenant("t2", "B", "kv_bbbbbb", Instant.parse("2026-09-22T08:00:00Z")),
                // A tenant deleted since stays in its cohort (no inflated retention).
                deleted(tenant("t3", "C", "kv_cccccc", Instant.parse("2026-09-24T08:00:00Z"))),
                tenant("t4", "D", "kv_dddddd", Instant.parse("2026-09-27T22:30:00Z"))); // Sun 23:30 WAT
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class))).thenReturn(List.of(
                activity("t1", w.plusWeeks(1)), activity("t2", w.plusWeeks(1)), activity("t4", w.plusWeeks(1)),
                activity("t1", w.plusWeeks(2)),
                activity("t1", w.plusWeeks(3)), activity("t2", w.plusWeeks(3)), // current week: in progress
                activity("t4", w))); // signup week itself is not a retention column

        RetentionReport report = service.getRetention(null);

        assertThat(report.weeks()).isEqualTo(8);
        assertThat(report.cohorts()).hasSize(12);
        assertThat(report.cohorts().get(0).weekStart()).isEqualTo(LocalDate.of(2026, 10, 12));
        RetentionCohort cohort = report.cohorts().get(3);
        assertThat(cohort.weekStart()).isEqualTo(w);
        assertThat(cohort.tenantCount()).isEqualTo(4);
        assertThat(cohort.retention()).hasSize(8);
        assertThat(cohort.retention().get(0)).isEqualTo(0.75);
        assertThat(cohort.retention().get(1)).isEqualTo(0.25);
        // W+3 = current week (Mon 12 → Sun 18 Oct, today Tue 13): not finished → null, like future weeks.
        assertThat(cohort.retention().subList(2, 8)).containsOnlyNulls();
        // Last week's cohort: its W+1 is the current, unfinished week.
        assertThat(report.cohorts().get(1).retention()).containsOnlyNulls();
        // Current week cohort: every column is in the future.
        assertThat(report.cohorts().get(0).retention()).containsOnlyNulls();
        // Empty past cohort: null cells (no division by zero).
        assertThat(report.cohorts().get(5).tenantCount()).isZero();
        assertThat(report.cohorts().get(5).retention()).containsOnlyNulls();
    }

    @Test
    @DisplayName("retention reads activity weeks from the first retention week of the oldest cohort")
    void retention_activityWindow() {
        stubTenants();
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class))).thenReturn(List.of());

        RetentionReport report = service.getRetention("4");

        assertThat(report.weeks()).isEqualTo(4);
        assertThat(report.cohorts().get(11).weekStart()).isEqualTo(LocalDate.of(2026, 7, 27));
        verify(jdbc).queryForList(argThat((String s) -> s.contains("ISODOW")
                && s.contains("t.id <> '00000000-0000-0000-0000-000000000000'::uuid")), eq(LocalDate.of(2026, 8, 3)));
    }

    @ParameterizedTest
    @CsvSource({"0", "13", "x"})
    @DisplayName("retention weeks out of 1..12 → VALIDATION_ERROR")
    void retention_invalidWeeks(String weeks) {
        assertValidationError(() -> service.getRetention(weeks));
    }

    // ── Top stores ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("top stores: GMV desc across chunks, basket, growth; previous GMV 0 → null (« Nouveau »)")
    void topStores_rankingAndGrowth() {
        stubTenants(tenant("t1", "Boutique A", "kv_aaaaaa", null), tenant("t2", null, "kv_bbbbbb", null));
        stubChunkRows(
                row("tenant_id", "t1", "store_name", "Akwa", "gmv", 30_000L, "transactions", 4L, "previous_gmv", 20_000L),
                row("tenant_id", "t2", "store_name", "Bonapriso", "gmv", 50_000L, "transactions", 3L, "previous_gmv", 0L));

        TopStoresReport report = service.getTopStores(null, null);

        assertThat(report.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(report.to()).isEqualTo(TODAY);
        assertThat(report.stores()).extracting(TopStore::storeName).containsExactly("Bonapriso", "Akwa");
        TopStore first = report.stores().get(0);
        assertThat(first.tenantName()).isEqualTo("Sans nom");
        assertThat(first.averageBasket()).isEqualTo(16_667);
        assertThat(first.growthPercent()).isNull();
        assertThat(report.stores().get(1).tenantName()).isEqualTo("Boutique A");
        assertThat(report.stores().get(1).growthPercent()).isEqualTo(50.0);
        assertThat(report.stores().get(1).averageBasket()).isEqualTo(7_500);
        // Stores without a sale in the period never rank; each chunk returns its own top 20 only.
        assertThat(outer("U")).contains("WHERE transactions > 0").contains("ORDER BY gmv DESC").endsWith("LIMIT 20");
    }

    @Test
    @DisplayName("top stores: binds current start ×3, previous start (equal length) and exclusive end")
    void topStores_windowBinding() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getTopStores("2026-10-01", "2026-10-10");

        OffsetDateTime cur = OffsetDateTime.parse("2026-09-30T23:00:00Z");
        assertThat(branch(FULL).params()).containsExactly(cur, cur, cur,
                OffsetDateTime.parse("2026-09-20T23:00:00Z"), OffsetDateTime.parse("2026-10-10T23:00:00Z"));
    }

    @Test
    @DisplayName("top stores: STORE type only (warehouses never rank), sales bounded to the start of tomorrow")
    void topStores_storeTypeAndFutureBound() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getTopStores("2026-10-01", "2026-10-20"); // `to` in the future

        Branch branch = branch(FULL);
        assertThat(branch.sql()).contains("JOIN kv_aaaaaa.stores st ON st.id = sa.store_id AND st.type = 'STORE'");
        assertThat(branch.params().get(4)).isEqualTo(OffsetDateTime.parse("2026-10-13T23:00:00Z"));
    }

    @Test
    @DisplayName("19.11b: tenants without sales or stores are not branches of the top stores statement")
    void topStores_onlyTenantsWithBothTables() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null), tenant("t2", "B", "kv_bbbbbb", null));
        when(crossTenant.withPresence(anyList())).thenAnswer(inv -> {
            List<TenantBranch> in = inv.getArgument(0);
            return List.of(in.get(0).withTables(EnumSet.of(TenantTable.SALES_CORE, TenantTable.STORES)),
                    in.get(1).withTables(EnumSet.of(TenantTable.STORES)));
        });

        service.getTopStores(null, null);

        assertThat(queriedTenants()).containsExactly("t1");
    }

    @Test
    @DisplayName("top stores keeps the 20 best of the merged chunks")
    void topStores_limit20() {
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));
        Map<String, Object>[] rows = new Map[25];
        for (int i = 0; i < 25; i++) {
            rows[i] = row("tenant_id", "t1", "store_name", "S" + i, "gmv", 1000L + i, "transactions", 1L,
                    "previous_gmv", 0L);
        }
        stubChunkRows(rows);

        assertThat(service.getTopStores(null, null).stores()).hasSize(20)
                .first().extracting(TopStore::storeName).isEqualTo("S24");
    }

    @ParameterizedTest(name = "from={0} to={1}")
    @CsvSource(value = {
            "2026-02-01,2026-01-01",   // from > to
            "2024-01-01,2025-01-01",   // 367 days
            "2026-01-01,",             // to missing
            ",2026-01-01",             // from missing
            "01/01/2026,2026-01-31",   // not ISO
            "1999-12-31,2000-01-02"})  // year < 2000
    @DisplayName("bad range → VALIDATION_ERROR, nothing queried")
    void topStores_badRange(String from, String to) {
        assertValidationError(() -> service.getTopStores(from, to));
        verifyNoInteractions(jdbc, crossTenant);
    }

    @Test
    @DisplayName("growthPercent rounds to 1 decimal, negative allowed, null on previous 0")
    void growthPercent() {
        assertThat(AdminAnalyticsService.growthPercent(150, 100)).isEqualTo(50.0);
        assertThat(AdminAnalyticsService.growthPercent(50, 150)).isEqualTo(-66.7);
        assertThat(AdminAnalyticsService.growthPercent(10, 0)).isNull();
    }

    // ── Adoption ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adoption: share of the active tenants per feature (chunks summed); inactive tenants not queried")
    void adoption_rates() {
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class)))
                .thenReturn(List.of(row("tenant_id", "t1"), row("tenant_id", "t2")));
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null), tenant("t2", "B", "kv_bbbbbb", null),
                tenant("t3", "Dormant", "kv_cccccc", null));
        // [active, sales, inventory, transfers, whatsapp, stock alerts, sync] per chunk
        doReturn(List.of(new long[]{1, 1, 1, 0, 1, 0, 1}, new long[]{1, 1, 0, 0, 0, 1, 1}))
                .when(crossTenant).query(anyList(), any(), any(), any());

        AdoptionReport report = service.getAdoption();

        assertThat(report.activeTenants()).isEqualTo(2);
        assertThat(report.features()).extracting(FeatureAdoption::feature).containsExactly(
                "SALES", "INVENTORY", "TRANSFERS", "WHATSAPP_REPORTS", "STOCK_ALERTS", "OFFLINE_SYNC");
        assertThat(report.features()).extracting(FeatureAdoption::rate)
                .containsExactly(1.0, 0.5, 0.0, 0.5, 0.5, 1.0);
        assertThat(queriedTenants()).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    @DisplayName("adoption SQL covers every feature source with the 30-day window")
    void adoption_sql() {
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class)))
                .thenReturn(List.of(row("tenant_id", "t1")));
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getAdoption();

        Branch branch = branch(FULL);
        assertThat(branch.params()).hasSize(12);
        for (int i = 0; i < 12; i += 2) {
            assertThat(branch.params().get(i)).isEqualTo(OffsetDateTime.parse("2026-09-13T23:00:00Z"));
            assertThat(branch.params().get(i + 1)).isEqualTo(OffsetDateTime.parse("2026-10-13T23:00:00Z"));
        }
        assertThat(branch.sql())
                .contains("kv_aaaaaa.inventory_sessions")
                .contains("kv_aaaaaa.stock_transfers")
                .contains("kv_aaaaaa.reports WHERE delivery_status = 'SENT'")
                .contains("kv_aaaaaa.tenant_preferences")
                .contains("cooldown_type = 'STOCK_ALERT'")
                .contains("kv_aaaaaa.sync_operations_log");
    }

    @Test
    @DisplayName("19.11b: adoption — a feature whose module table is absent reads false (tenant still counted)")
    void adoption_missingModuleTableReadsFalse() {
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class)))
                .thenReturn(List.of(row("tenant_id", "t1")));
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        service.getAdoption();

        Branch branch = branch(new TenantBranch(FULL.id(), "A", "kv_aaaaaa", "ACTIVE", null,
                EnumSet.of(TenantTable.SALES_CORE, TenantTable.STORES)));
        assertThat(branch.sql()).contains("kv_aaaaaa.sales").doesNotContain("inventory_sessions")
                .doesNotContain("stock_transfers").doesNotContain(".reports").doesNotContain("sync_operations_log")
                .contains("false AS inventory").contains("false AS offline_sync");
        assertThat(branch.params()).hasSize(2);
    }

    @Test
    @DisplayName("no active tenant → every rate 0")
    void adoption_noActiveTenant() {
        when(jdbc.queryForList(contains("user_daily_activity"), any(Object[].class))).thenReturn(List.of());
        stubTenants(tenant("t1", "A", "kv_aaaaaa", null));

        AdoptionReport report = service.getAdoption();

        assertThat(report.activeTenants()).isZero();
        assertThat(report.features()).allSatisfy(f -> assertThat(f.rate()).isZero());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record Branch(String sql, List<Object> params) {
    }

    /** The branch SQL and bound values the last cross-tenant statement would write for {@code tenant}. */
    private Branch branch(TenantBranch tenant) {
        ArgumentCaptor<CrossTenantSql.BranchSql> writer = ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant, atLeastOnce()).query(anyList(), writer.capture(), any(), any());
        List<Object> params = new ArrayList<>();
        String sql = writer.getValue().sql(tenant, params);
        return new Branch(sql, params);
    }

    /** The outer statement of the last cross-tenant call around {@code union}. */
    @SuppressWarnings("unchecked")
    private String outer(String union) {
        ArgumentCaptor<UnaryOperator<String>> outer = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(crossTenant, atLeastOnce()).query(anyList(), any(), outer.capture(), any());
        return outer.getValue().apply(union);
    }

    /** Ids of the tenants of the last cross-tenant statement. */
    @SuppressWarnings("unchecked")
    private List<String> queriedTenants() {
        ArgumentCaptor<List<TenantBranch>> tenants = ArgumentCaptor.forClass(List.class);
        verify(crossTenant, atLeastOnce()).query(tenants.capture(), any(), any(), any());
        return tenants.getValue().stream().map(TenantBranch::id).toList();
    }

    /** The cross-tenant statement returns {@code rows} (by column label) through the service's own row mapper. */
    @SafeVarargs
    private void stubChunkRows(Map<String, Object>... rows) {
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(3);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < rows.length; i++) out.add(mapper.mapRow(resultSet(rows[i]), i));
            return out;
        }).when(crossTenant).query(anyList(), any(), any(), any());
    }

    private static ResultSet resultSet(Map<String, Object> row) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(i -> {
            Object v = row.get(i.<String>getArgument(0));
            return v == null ? null : v.toString();
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(i -> {
            Object v = row.get(i.<String>getArgument(0));
            return v instanceof Number n ? n.longValue() : 0L;
        });
        lenient().when(rs.getTimestamp(anyString())).thenAnswer(i -> (Timestamp) row.get(i.<String>getArgument(0)));
        return rs;
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private void stubDistinctUsers(LocalDate from, LocalDate to, long count) {
        when(jdbc.queryForObject(contains("COUNT(DISTINCT a.user_id)"), eq(Long.class), eq(from), eq(to)))
                .thenReturn(count);
    }

    /** The port answers with the entries of the tenants it receives (TENANT bucket) or all entries (time buckets). */
    private void stubRevenue(Bucket bucket, Map<String, Totals> totals) {
        lenient().when(revenue.totals(any(), any(), any(), eq(bucket))).thenAnswer(inv -> {
            List<TenantBranch> tenants = inv.getArgument(0);
            if (bucket != Bucket.TENANT) return totals;
            Map<String, Totals> out = new HashMap<>();
            tenants.forEach(t -> { if (totals.containsKey(t.id())) out.put(t.id(), totals.get(t.id())); });
            return out;
        });
    }

    @SafeVarargs
    private void stubTenants(Map<String, Object>... tenants) {
        when(jdbc.queryForList(contains("FROM public.tenants"))).thenReturn(List.of(tenants));
    }

    private static Map<String, Object> tenant(String id, String name, String schema, Instant createdAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("schema_name", schema);
        m.put("created_at", createdAt == null ? null : Timestamp.from(createdAt));
        m.put("status", "ACTIVE");
        return m;
    }

    private static Map<String, Object> deleted(Map<String, Object> tenant) {
        tenant.put("status", "DELETED");
        return tenant;
    }

    private static Map<String, Object> activity(String tenantId, LocalDate weekStart) {
        return row("tenant_id", tenantId, "week_start", Date.valueOf(weekStart));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
