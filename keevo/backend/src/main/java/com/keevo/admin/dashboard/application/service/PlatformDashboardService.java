package com.keevo.admin.dashboard.application.service;

import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.AccountFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.GrowthPoint;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.Overview;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.RevenueFigures;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.TopAccount;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.UserFigures;
import com.keevo.admin.dashboard.domain.port.in.PlatformDashboardUseCase;
import com.keevo.admin.dashboard.domain.port.out.PlatformDashboardReadPort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Bucket;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort.Totals;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Story 20.13 — the super-admin platform dashboard inherited from Bref. Accounts = live tenants (a migrated Bref
 * owner is one tenant), users = distinct non-super-admin people, revenue = the 20.9 single source through
 * {@link PlatformRevenuePort} (a POS sale bridged to an invoice counts once): no double counting (R4). Months are
 * Africa/Douala. Every figure is one statement per chunk of tenants (see the read port); overview and growth are cached
 * {@code keevo.admin.dashboard.cache-seconds} (default 120 s) — figures may lag by that much; read-only.
 */
@Service
public class PlatformDashboardService implements PlatformDashboardUseCase {

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final int DEFAULT_MONTHS = 7;
    static final int MAX_MONTHS = 24;
    static final int DEFAULT_TOP = 5;
    static final int MAX_TOP = 20;
    static final int DEFAULT_TOP_DAYS = 30;
    static final int MAX_TOP_DAYS = 366;
    static final int DEFAULT_ACTIVITY = 10;
    static final int MAX_ACTIVITY = 50;
    private static final Locale FR = Locale.FRENCH;

    private final PlatformDashboardReadPort reader;
    private final PlatformRevenuePort revenue;
    private final Clock clock;
    /** Short-lived cache of the platform-wide figures (overview, growth): they scan every tenant. */
    private final Cache<String, Object> cache;

    @Autowired
    public PlatformDashboardService(PlatformDashboardReadPort reader, PlatformRevenuePort revenue, Clock clock,
                                    @Value("${keevo.admin.dashboard.cache-seconds:120}") long cacheSeconds) {
        this.reader = reader;
        this.revenue = revenue;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().maximumSize(64).expireAfterWrite(Duration.ofSeconds(cacheSeconds)).build();
    }

    /** Without cache (tests). */
    public PlatformDashboardService(PlatformDashboardReadPort reader, PlatformRevenuePort revenue, Clock clock) {
        this(reader, revenue, clock, 0);
    }

    @Override
    public Overview getOverview() {
        return (Overview) cache.get("overview", k -> computeOverview());
    }

    private Overview computeOverview() {
        YearMonth month = YearMonth.from(today());
        Instant monthStart = start(month);
        Instant previousStart = start(month.minusMonths(1));
        List<TenantBranch> tenants = reader.liveTenants();

        PlatformDashboardReadPort.UserCounts users = reader.userCounts(monthStart, previousStart);
        PlatformDashboardReadPort.AccountCounts accounts = reader.accountCounts(monthStart, previousStart);
        // One pass: all-time revenue by month — the total is the sum of the months, this / last month two keys.
        Map<String, Totals> monthly = revenue.totals(tenants, null, null, Bucket.MONTH);
        long thisMonth = monthly.getOrDefault(month.toString(), Totals.ZERO).revenue();
        long lastMonth = monthly.getOrDefault(month.minusMonths(1).toString(), Totals.ZERO).revenue();
        long total = monthly.values().stream().mapToLong(Totals::revenue).sum();

        return new Overview(
                new UserFigures(users.total(), users.active(), users.newThisMonth(), users.newLastMonth(),
                        PlatformDashboardViews.trendPercent(users.newThisMonth(), users.newLastMonth())),
                new AccountFigures(accounts.total(), accounts.active(), accounts.suspended(), accounts.newThisMonth(),
                        accounts.newLastMonth(),
                        PlatformDashboardViews.trendPercent(accounts.newThisMonth(), accounts.newLastMonth())),
                reader.storeFigures(tenants, monthStart),
                reader.documentFigures(tenants),
                new RevenueFigures(total, thisMonth, lastMonth, PlatformDashboardViews.trendPercent(thisMonth, lastMonth)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GrowthPoint> getGrowth(String months) {
        int count = AdminParams.bounded(months, "Le nombre de mois", DEFAULT_MONTHS, 1, MAX_MONTHS);
        return (List<GrowthPoint>) cache.get("growth:" + count, k -> computeGrowth(count));
    }

    private List<GrowthPoint> computeGrowth(int count) {
        YearMonth current = YearMonth.from(today());
        YearMonth first = current.minusMonths(count - 1L);
        Instant from = start(first);
        Instant to = start(current.plusMonths(1));
        List<TenantBranch> tenants = reader.liveTenants();

        Map<String, Long> users = reader.newUsersByMonth(from, to);
        Map<String, Long> accounts = reader.newAccountsByMonth(from, to);
        Map<String, Long> stores = reader.newStoresByMonth(tenants, from, to);
        Map<String, Long> invoices = reader.invoicesByMonth(tenants, from, to);
        Map<String, Totals> sales = revenue.totals(tenants, from, to, Bucket.MONTH);

        List<GrowthPoint> points = new ArrayList<>(count);
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            String key = m.toString();
            Totals t = sales.getOrDefault(key, Totals.ZERO);
            points.add(new GrowthPoint(key, label(m), m.getYear(), users.getOrDefault(key, 0L),
                    accounts.getOrDefault(key, 0L), stores.getOrDefault(key, 0L), invoices.getOrDefault(key, 0L),
                    t.revenue(), t.documents()));
        }
        return points;
    }

    @Override
    public List<TopAccount> getTopAccounts(String limit, String days) {
        int top = AdminParams.bounded(limit, "Le nombre de comptes", DEFAULT_TOP, 1, MAX_TOP);
        int window = AdminParams.bounded(days, "Le nombre de jours", DEFAULT_TOP_DAYS, 1, MAX_TOP_DAYS);
        LocalDate today = today();
        Instant from = today.minusDays(window - 1L).atStartOfDay(WAT).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(WAT).toInstant();
        List<TenantBranch> tenants = reader.liveTenants();
        Map<String, TenantBranch> byId = tenants.stream()
                .collect(Collectors.toMap(TenantBranch::id, Function.identity(), (a, b) -> a));

        List<Map.Entry<String, Totals>> ranked = revenue.totals(tenants, from, to, Bucket.TENANT).entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()) && e.getValue().revenue() > 0)
                .sorted(Comparator.<Map.Entry<String, Totals>>comparingLong(e -> e.getValue().revenue()).reversed()
                        .thenComparing(e -> byId.get(e.getKey()).name(), String.CASE_INSENSITIVE_ORDER))
                .limit(top)
                .toList();
        List<TenantBranch> winners = ranked.stream().map(e -> byId.get(e.getKey())).toList();
        Map<String, Long> stores = reader.storeCounts(winners);
        Map<String, String> owners = reader.ownerNames(winners.stream().map(TenantBranch::id).toList());
        return ranked.stream().map(e -> {
            TenantBranch t = byId.get(e.getKey());
            return new TopAccount(t.id(), t.name(), owners.getOrDefault(t.id(), com.keevo.admin.AdminLabels.OWNER),
                    t.status(), PlatformDashboardViews.accountStatusLabel(t.status()),
                    stores.getOrDefault(t.id(), 0L), e.getValue().documents(), e.getValue().revenue());
        }).toList();
    }

    @Override
    public List<ActivityItem> getActivity(String limit) {
        int n = AdminParams.bounded(limit, "Le nombre d'événements", DEFAULT_ACTIVITY, 1, MAX_ACTIVITY);
        return reader.activity(reader.liveTenants(), n);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(WAT));
    }

    private static Instant start(YearMonth month) {
        return month.atDay(1).atStartOfDay(WAT).toInstant();
    }

    /** « nov. 2026 » */
    static String label(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.SHORT, FR) + " " + month.getYear();
    }
}
