package com.keevo.admin.dashboard.application.service;

import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.DocumentFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.GrowthPoint;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.Overview;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.StoreFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.TopAccount;
import com.keevo.admin.dashboard.domain.port.out.PlatformDashboardReadPort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Bucket;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Totals;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("20.13 — platform dashboard service (Bref dashboard overview / growth / top / activity)")
class PlatformDashboardServiceTest {

    /** Sunday 2026-11-08 12:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-11-08T11:00:00Z");
    private static final Instant NOV = Instant.parse("2026-10-31T23:00:00Z");
    private static final Instant OCT = Instant.parse("2026-09-30T23:00:00Z");
    private static final Instant DEC = Instant.parse("2026-11-30T23:00:00Z");

    @Mock PlatformDashboardReadPort reader;
    @Mock PlatformRevenuePort revenue;

    PlatformDashboardService service;

    private final TenantBranch a = new TenantBranch("ta", "Alpha", "kv_aaaaaa", "ACTIVE", null);
    private final TenantBranch b = new TenantBranch("tb", "Beta", "kv_bbbbbb", "SUSPENDED", null);
    private final TenantBranch c = new TenantBranch("tc", "Gamma", "kv_cccccc", "ACTIVE", null);

    @BeforeEach
    void setUp() {
        service = new PlatformDashboardService(reader, revenue, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(reader.liveTenants()).thenReturn(List.of(a, b, c));
    }

    @Test
    @DisplayName("overview: trends vs last month (null when last month is 0), revenue this / last month and all time")
    void overview() {
        when(reader.userCounts(NOV, OCT)).thenReturn(new PlatformDashboardReadPort.UserCounts(10, 8, 3, 2));
        when(reader.accountCounts(NOV, OCT)).thenReturn(new PlatformDashboardReadPort.AccountCounts(3, 2, 1, 1, 0));
        when(reader.storeFigures(anyList(), eq(NOV))).thenReturn(new StoreFigures(4, 3, 1));
        when(reader.documentFigures(anyList())).thenReturn(new DocumentFigures(5, 1, 1, 2, 1, 2));
        // one pass: all-time revenue by month (total = sum of the months)
        when(revenue.totals(anyList(), isNull(), isNull(), eq(Bucket.MONTH))).thenReturn(Map.of(
                "2026-11", new Totals(15_000, 3), "2026-10", new Totals(10_000, 2), "2025-03", new Totals(17_000, 4)));

        Overview o = service.getOverview();

        assertThat(o.users().trendPercent()).isEqualTo(50.0);
        assertThat(o.accounts().trendPercent()).isNull();
        assertThat(o.accounts().suspended()).isEqualTo(1);
        assertThat(o.revenue().total()).isEqualTo(42_000); // 15 000 + 10 000 + 17 000
        assertThat(o.revenue().thisMonth()).isEqualTo(15_000);
        assertThat(o.revenue().lastMonth()).isEqualTo(10_000);
        assertThat(o.revenue().trendPercent()).isEqualTo(50.0);
        assertThat(o.documents().overdue()).isEqualTo(1);
        verify(revenue).totals(eq(List.of(a, b, c)), isNull(), isNull(), eq(Bucket.MONTH));
        verifyNoMoreInteractions(revenue); // a single revenue pass
    }

    @Test
    @DisplayName("overview and growth are cached (120 s by default): a second call reads nothing")
    void cached() {
        PlatformDashboardService cachedService =
                new PlatformDashboardService(reader, revenue, Clock.fixed(NOW, ZoneOffset.UTC), 120);
        when(reader.userCounts(NOV, OCT)).thenReturn(new PlatformDashboardReadPort.UserCounts(1, 1, 0, 0));
        when(reader.accountCounts(NOV, OCT)).thenReturn(new PlatformDashboardReadPort.AccountCounts(1, 1, 0, 0, 0));
        when(revenue.totals(anyList(), isNull(), isNull(), eq(Bucket.MONTH))).thenReturn(Map.of());

        Overview first = cachedService.getOverview();
        Overview second = cachedService.getOverview();

        assertThat(second).isSameAs(first);
        verify(reader, times(1)).userCounts(NOV, OCT);
    }

    @Test
    @DisplayName("growth: one zero-filled point per WAT month, French label, revenue from the source")
    void growth() {
        Instant aug = Instant.parse("2026-07-31T23:00:00Z");
        when(reader.newUsersByMonth(aug, DEC)).thenReturn(Map.of("2026-09", 4L));
        when(reader.newAccountsByMonth(aug, DEC)).thenReturn(Map.of("2026-11", 1L));
        when(reader.newStoresByMonth(anyList(), eq(aug), eq(DEC))).thenReturn(Map.of());
        when(reader.invoicesByMonth(anyList(), eq(aug), eq(DEC))).thenReturn(Map.of("2026-10", 2L));
        when(revenue.totals(anyList(), eq(aug), eq(DEC), eq(Bucket.MONTH)))
                .thenReturn(Map.of("2026-10", new Totals(7_000, 3)));

        List<GrowthPoint> points = service.getGrowth("4");

        assertThat(points).extracting(GrowthPoint::month).containsExactly("2026-08", "2026-09", "2026-10", "2026-11");
        assertThat(points.get(0).label()).isEqualTo("août 2026");
        assertThat(points.get(1).newUsers()).isEqualTo(4);
        assertThat(points.get(2).invoicesIssued()).isEqualTo(2);
        assertThat(points.get(2).revenue()).isEqualTo(7_000);
        assertThat(points.get(2).documents()).isEqualTo(3);
        assertThat(points.get(3).newAccounts()).isEqualTo(1);
        assertThat(points.get(3).revenue()).isZero();
    }

    @Test
    @DisplayName("top accounts: revenue desc over the WAT window, zero revenue left out, owner and store counts")
    void topAccounts() {
        Instant from = Instant.parse("2026-11-01T23:00:00Z"); // 7 days ending today
        Instant to = Instant.parse("2026-11-08T23:00:00Z");
        when(revenue.totals(anyList(), eq(from), eq(to), eq(Bucket.TENANT))).thenReturn(Map.of(
                "ta", new Totals(5_000, 2), "tb", new Totals(9_000, 1), "tc", new Totals(0, 0),
                "gone", new Totals(99_000, 9)));
        when(reader.storeCounts(List.of(b, a))).thenReturn(Map.of("ta", 2L, "tb", 1L));
        when(reader.ownerNames(List.of("tb", "ta"))).thenReturn(Map.of("tb", "Awa Ngo"));

        List<TopAccount> top = service.getTopAccounts("5", "7");

        assertThat(top).extracting(TopAccount::tenantId).containsExactly("tb", "ta");
        assertThat(top.get(0).ownerName()).isEqualTo("Awa Ngo");
        assertThat(top.get(0).statusLabel()).isEqualTo("Suspendu");
        assertThat(top.get(1).ownerName()).isEqualTo("Propriétaire");
        assertThat(top.get(1).storeCount()).isEqualTo(2);
        assertThat(top.get(1).documents()).isEqualTo(2);
    }

    @Test
    @DisplayName("activity: limit defaults to 10")
    void activityDefault() {
        when(reader.activity(anyList(), eq(10))).thenReturn(List.of());

        assertThat(service.getActivity(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"growth,0", "growth,25", "growth,x", "top,21", "top,0", "days,367", "activity,51", "activity,0"})
    @DisplayName("out-of-range parameters → 400 VALIDATION_ERROR")
    void invalidParams(String which, String value) {
        Runnable call = switch (which) {
            case "growth" -> () -> service.getGrowth(value);
            case "top" -> () -> service.getTopAccounts(value, null);
            case "days" -> () -> service.getTopAccounts(null, value);
            default -> () -> service.getActivity(value);
        };
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
