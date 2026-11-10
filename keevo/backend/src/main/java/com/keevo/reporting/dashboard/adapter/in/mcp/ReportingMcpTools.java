package com.keevo.reporting.dashboard.adapter.in.mcp;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import com.keevo.reporting.leaderboard.application.service.LeaderboardAudience;
import com.keevo.reporting.leaderboard.domain.model.SellerLeaderboardEntry;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.profitability.domain.model.ProfitabilitySummary;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.reporting.profitability.domain.model.SortOption;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.in.GetProfitabilitySummaryUseCase;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.ai.AiToolArguments;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code reporting} module, each mirroring a REST handler (same gates, same defaults):
 * {@code DashboardController} (OWNER, module {@code reporting}), {@code ProfitabilityController} (OWNER, module
 * {@code profitability}) and {@code LeaderboardController} (every member role, module {@code advanced-reports},
 * sharing rule {@link LeaderboardAudience}). Results carry names and amounts (FCFA), never product, seller or sale ids.
 */
@Component
public class ReportingMcpTools {

    /** Same default window as {@code ProfitabilityController}: the last 30 days. */
    static final int DEFAULT_PERIOD_DAYS = 30;
    static final int MAX_SELLERS = 50;

    static final Map<String, String> STORE_STATUS = Map.of(
            "STABLE", "Stable", "ATTENTION", "À surveiller", "EN_BAISSE", "En baisse");

    private final GetDashboardSummaryUseCase dashboard;
    private final GetProfitabilitySummaryUseCase profitability;
    private final GetStorePerformanceUseCase storePerformance;
    private final GetSellerLeaderboardUseCase leaderboard;
    private final LeaderboardAudience audience;
    private final Clock clock;

    public ReportingMcpTools(GetDashboardSummaryUseCase dashboard, GetProfitabilitySummaryUseCase profitability,
                             GetStorePerformanceUseCase storePerformance, GetSellerLeaderboardUseCase leaderboard,
                             TenantPreferencesQueryPort preferences, EntitlementService entitlements, Clock clock) {
        this.dashboard = dashboard;
        this.profitability = profitability;
        this.storePerformance = storePerformance;
        this.leaderboard = leaderboard;
        this.audience = new LeaderboardAudience(preferences, entitlements);
        this.clock = clock;
    }

    // ── activity_overview ─────────────────────────────────────────────────────────────────────────────────

    public record ProductFigure(String product, int unitsSold, long revenue, double sharePercent) {
    }

    public record DayAmount(String date, long amount) {
    }

    public record StoreToday(String store, long revenueToday, long revenueYesterday, int employees, String trend) {
    }

    public record PeriodFigure(long revenue, int salesCount) {
    }

    public record ActivityOverview(long revenueToday, long revenueYesterday, long revenueDayBeforeYesterday,
                                   double trendPercentVsYesterday, int salesCountToday,
                                   int salesCountThisMonth, int salesCountPreviousMonth,
                                   long averageBasketThisMonth, long averageBasketPreviousMonth,
                                   int lowStockProducts, PeriodFigure thisWeek, PeriodFigure thisMonth,
                                   PeriodFigure thisYear, PeriodFigure sinceStart,
                                   List<ProductFigure> bestSellersThisWeek, List<ProductFigure> worstSellersThisWeek,
                                   List<DayAmount> revenueLast30Days, List<StoreToday> stores) {
    }

    @Tool(name = "activity_overview", description = "Tableau de bord du propriétaire : chiffre d'affaires du jour, "
            + "d'hier et d'avant-hier, tendance, nombre de ventes et panier moyen du mois (et du mois précédent), "
            + "totaux de la semaine, du mois, de l'année et depuis le début, produits les plus et les moins vendus de "
            + "la semaine, chiffre d'affaires des 30 derniers jours, situation du jour de chaque boutique, nombre de "
            + "produits en stock bas. Montants en FCFA. Réservé au propriétaire.")
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
    public ActivityOverview activityOverview() {
        DashboardSummary s = dashboard.execute(
                new GetDashboardSummaryUseCase.GetDashboardSummaryQuery(TenantContext.getCurrentTenant()));
        DashboardSummary.PeriodTotals totals = s.periodTotals() != null ? s.periodTotals()
                : DashboardSummary.PeriodTotals.EMPTY;
        return new ActivityOverview(s.todayCA(), s.yesterdayCA(), s.dayBeforeYesterdayCA(), s.heroTrendPercent(),
                s.todaySalesCount(), s.totalTransactionsMonth(), s.prevMonthTransactions(), s.averageBasketMonth(),
                s.prevMonthAverageBasket(), s.lowStockCount(), period(totals.week()), period(totals.month()),
                period(totals.year()), period(totals.allTime()), products(s.weeklyTopProducts()),
                products(s.weeklyWorstProducts()), days(s.dailyCALast30()), stores(s.storeOverviews()));
    }

    private static PeriodFigure period(DashboardSummary.PeriodTotal t) {
        return t == null ? new PeriodFigure(0, 0) : new PeriodFigure(t.amount(), t.salesCount());
    }

    private static List<ProductFigure> products(List<DashboardSummary.TopProductEntry> entries) {
        return entries == null ? List.of() : entries.stream()
                .map(e -> new ProductFigure(e.productName(), e.unitsSold(), e.revenue(), e.sharePercent())).toList();
    }

    private static List<DayAmount> days(List<DashboardSummary.DailyCAEntry> entries) {
        return entries == null ? List.of() : entries.stream().map(e -> new DayAmount(e.date(), e.amount())).toList();
    }

    private static List<StoreToday> stores(List<DashboardSummary.StoreOverviewEntry> entries) {
        return entries == null ? List.of() : entries.stream()
                .map(e -> new StoreToday(e.storeName(), e.todayCA(), e.yesterdayCA(), e.employeeCount(),
                        STORE_STATUS.getOrDefault(e.statusLevel(), "Stable")))
                .toList();
    }

    // ── revenue_summary / compare_revenue_periods ─────────────────────────────────────────────────────────

    public record RevenueSummary(LocalDate from, LocalDate to, long revenue, long revenueWithKnownMargin,
                                 long costOfKnownMargin, long grossMargin, double marginPercentOnCost,
                                 long revenueWithUnknownMargin, int linesWithUnknownMargin) {
    }

    public record RevenueComparison(RevenueSummary first, RevenueSummary second, long revenueDifference,
                                    Double revenueChangePercent) {
    }

    @Tool(name = "revenue_summary", description = "Chiffre d'affaires d'une période (ventes en caisse et factures, "
            + "avoirs déduits) avec la marge brute des lignes dont le coût est connu et la part sans marge connue. "
            + "Toutes les boutiques, ou une seule avec storeId. Par défaut les 30 derniers jours. Montants en FCFA. "
            + "Réservé au propriétaire.")
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(value = "profitability", access = RequiresEntitlement.Access.READ)
    public RevenueSummary revenueSummary(
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ)") LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ), par défaut aujourd'hui")
            LocalDate to,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores) ; "
                    + "absent = toutes les boutiques") UUID storeId) {
        LocalDate[] range = range(from, to);
        return summary(range[0], range[1], storeId);
    }

    @Tool(name = "compare_revenue_periods", description = "Compare le chiffre d'affaires et la marge de deux périodes "
            + "(par exemple ce mois-ci et le mois dernier) : les deux résumés, l'écart et l'évolution en pourcentage "
            + "de la seconde par rapport à la première. Toutes les boutiques, ou une seule avec storeId. "
            + "Montants en FCFA. Réservé au propriétaire.")
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(value = "profitability", access = RequiresEntitlement.Access.READ)
    public RevenueComparison compareRevenuePeriods(
            @ToolParam(description = "Premier jour de la période de référence (AAAA-MM-JJ)") LocalDate firstFrom,
            @ToolParam(description = "Dernier jour de la période de référence (AAAA-MM-JJ)") LocalDate firstTo,
            @ToolParam(description = "Premier jour de la période comparée (AAAA-MM-JJ)") LocalDate secondFrom,
            @ToolParam(description = "Dernier jour de la période comparée (AAAA-MM-JJ)") LocalDate secondTo,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores) ; "
                    + "absent = toutes les boutiques") UUID storeId) {
        if (firstFrom == null || firstTo == null || secondFrom == null || secondTo == null) {
            throw new InvalidRequestParameterException("period", "both periods are required",
                    "Indiquez les deux périodes à comparer (dates de début et de fin).");
        }
        checkOrder(firstFrom, firstTo);
        checkOrder(secondFrom, secondTo);
        RevenueSummary first = summary(firstFrom, firstTo, storeId);
        RevenueSummary second = summary(secondFrom, secondTo, storeId);
        long difference = second.revenue() - first.revenue();
        // no meaningful percentage from a zero or negative base (credit notes above sales)
        Double percent = first.revenue() <= 0 ? null
                : Math.round(difference * 1000.0 / first.revenue()) / 10.0;
        return new RevenueComparison(first, second, difference, percent);
    }

    private RevenueSummary summary(LocalDate from, LocalDate to, UUID storeId) {
        ProfitabilitySummary s = profitability.getSummary(new ProfitabilityQuery(TenantContext.getCurrentTenant(),
                from, to, SortOption.MARGIN_PCT_DESC, storeId));
        return new RevenueSummary(from, to, s.totalRevenue(), s.revenueWithKnownMargin(), s.costOfKnownMargin(),
                s.grossMarginXaf(), Math.round(s.marginPercent() * 10.0) / 10.0, s.revenueWithUnknownMargin(),
                s.unknownMarginLines());
    }

    // ── store_performance ─────────────────────────────────────────────────────────────────────────────────

    public record StoreRank(int rank, String store, long revenue, int salesCount, long averageBasket,
                            String bestSellingProduct, Double changePercentVsPreviousPeriod) {
    }

    @Tool(name = "store_performance", description = "Classement des boutiques sur une période : chiffre d'affaires, "
            + "nombre de ventes, panier moyen, produit le plus vendu et évolution par rapport à la période précédente "
            + "de même durée. Critère de classement : CA (défaut), SALES_COUNT ou AVG_BASKET. Par défaut les 30 "
            + "derniers jours. Montants en FCFA. Réservé au propriétaire.")
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(value = "profitability", access = RequiresEntitlement.Access.READ)
    public List<StoreRank> storePerformance(
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ)") LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ), par défaut aujourd'hui")
            LocalDate to,
            @ToolParam(required = false, description = "Critère : CA, SALES_COUNT ou AVG_BASKET") String metric) {
        LocalDate[] range = range(from, to);
        return storePerformance.getRanking(new GetStorePerformanceUseCase.StorePerformanceQuery(
                        TenantContext.getCurrentTenant(), range[0], range[1], RankingMetric.fromString(metric)))
                .stream()
                .map(e -> new StoreRank(e.rank(), e.storeName(), e.totalRevenue(), e.salesCount(), e.averageBasket(),
                        e.topProductName(), e.deltaPercent()))
                .toList();
    }

    // ── seller_ranking ────────────────────────────────────────────────────────────────────────────────────

    public record SellerRank(int rank, String seller, boolean owner, boolean me, int salesCount, long revenue,
                             long averageBasket) {
    }

    public record SellerRanking(LocalDate from, LocalDate to, List<SellerRank> sellers, boolean truncated) {
    }

    @Tool(name = "seller_ranking", description = "Classement des vendeurs sur une période : nombre de ventes, chiffre "
            + "d'affaires et panier moyen par vendeur. Critère : CA (défaut), SALES_COUNT ou AVG_BASKET. Par défaut "
            + "aujourd'hui. Le propriétaire voit toutes les boutiques (ou une seule avec storeId) ; un employé ne le "
            + "voit que si le propriétaire l'a partagé, pour sa boutique, les autres vendeurs anonymisés. "
            + "Montants en FCFA.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "advanced-reports", access = RequiresEntitlement.Access.READ)
    public SellerRanking sellerRanking(
            @ToolParam(required = false, description = "Premier jour inclus (AAAA-MM-JJ), par défaut le dernier jour")
            LocalDate from,
            @ToolParam(required = false, description = "Dernier jour inclus (AAAA-MM-JJ), par défaut aujourd'hui")
            LocalDate to,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), propriétaire "
                    + "seulement ; absent = toutes les boutiques") UUID storeId,
            @ToolParam(required = false, description = "Critère : CA, SALES_COUNT ou AVG_BASKET") String metric) {
        // same defaults as LeaderboardController: to = today, from = to
        LocalDate dateTo = to != null ? to : today();
        LocalDate dateFrom = from != null ? from : dateTo;
        checkOrder(dateFrom, dateTo);
        String tenant = TenantContext.getCurrentTenant();
        LeaderboardAudience.View view = audience.resolve(tenant, CurrentActor.isOwner(), storeId,
                CurrentActor::assignedStoreId);
        UUID caller = view.anonymised() ? CurrentActor.actorId() : null;
        List<SellerLeaderboardEntry> entries = leaderboard.getLeaderboard(new GetSellerLeaderboardUseCase
                .SellerLeaderboardQuery(tenant, dateFrom, dateTo, view.storeId(), RankingMetric.fromString(metric)));
        List<SellerRank> rows = entries.stream().limit(MAX_SELLERS)
                .map(e -> sellerRow(e, view.anonymised(), caller)).toList();
        return new SellerRanking(dateFrom, dateTo, rows, entries.size() > MAX_SELLERS);
    }

    /** Same anonymisation as {@code SellerLeaderboardEntryDto.anonymisedFor}: « Vendeur &lt;rang&gt; » but the caller. */
    static SellerRank sellerRow(SellerLeaderboardEntry e, boolean anonymised, UUID caller) {
        boolean me = anonymised && caller != null && caller.equals(e.sellerId());
        if (anonymised && !me) {
            return new SellerRank(e.rank(), "Vendeur " + e.rank(), false, false, e.salesCount(), e.totalRevenue(),
                    e.averageBasket());
        }
        return new SellerRank(e.rank(), e.sellerName(), e.owner(), me, e.salesCount(), e.totalRevenue(),
                e.averageBasket());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KeevoZones.WAT));
    }

    /** {@code to} defaults to today, {@code from} to 30 days ending {@code to} (ProfitabilityController). */
    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate dateTo = to != null ? to : today();
        LocalDate dateFrom = from != null ? from : dateTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        checkOrder(dateFrom, dateTo);
        return new LocalDate[]{dateFrom, dateTo};
    }

    static void checkOrder(LocalDate from, LocalDate to) {
        AiToolArguments.checkPeriod(from, to);
    }
}
