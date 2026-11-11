package com.keevo.admin.revenue.application.service;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.identity.api.PlanKind;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.subscription.api.OfferAdministration;
import com.keevo.admin.revenue.domain.model.*;
import com.keevo.admin.revenue.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

import static com.keevo.admin.revenue.domain.model.RevenueMetrics.WAT;

/**
 * AdminRevenueService — super admin revenue dashboard (Story 9-3, FR79).
 *
 * <p>Source of truth: {@code public.tenant_payments} (MRR, new paying tenants, revenue collected) and
 * {@code public.tenant_plan_events} (churn, trial conversion), both written by 9-2. There is no price list:
 * nothing is estimated from plans. The arithmetic lives in {@link RevenueMetrics}; this service loads the rows,
 * validates the parameters and reads the per-tenant-schema data (current plan counts, GMV before churn).
 *
 * <p>Scope: tenants not {@code DELETED}, never the KV-ADMIN system tenant. Plan counts only cover
 * ACTIVE / SUSPENDED / DELETION_PENDING tenants. Dates are WAT (Africa/Douala) calendar dates.
 *
 * <p>Deliberately NOT {@code @Transactional}: each per-schema statement runs on its own so a tenant whose
 * schema query fails (missing table, broken schema) is skipped without aborting the others; connection or
 * resource failures are rethrown (9-7 rule).
 *
 * <p>Story 19.11c (FUS-NFR11): the per-tenant-schema reads (current plan of every tenant, GMV of the churned tenants)
 * are chunked {@code UNION ALL} statements ({@link CrossTenantSql}, 100 tenants each) after one table-presence query —
 * never a statement per tenant.
 */
@Service
public class AdminRevenueService implements GetRevenueSummaryUseCase, GetRevenueTrendUseCase, GetChurnReportUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminRevenueService.class);

    static final int MAX_RANGE_MONTHS = 24;
    static final int TREND_MONTHS = 12;
    static final int GMV_WINDOW_DAYS = 30;
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 9999;
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");

    private static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final String IN_SCOPE =
            "t.status <> 'DELETED' AND t.id <> '" + SYSTEM_TENANT_ID + "'::uuid";

    /** Story 21.3 — usage packs paid in a range (partial index on confirmed_at of confirmed pack requests). */
    static final String PACK_SALES_SQL = "SELECT count(*), COALESCE(sum(p.amount_due_xaf), 0) "
            + "FROM public.payment_requests p JOIN public.tenants t ON t.id = p.tenant_id "
            + "WHERE p.kind = 'USAGE_PACK' AND p.status = 'CONFIRMED' AND p.confirmed_at >= ? AND p.confirmed_at < ? "
            + "AND " + IN_SCOPE;

    /** Story 24.5 (21.3 item) — the account credit the live companies hold (one aggregate on the credit table). */
    static final String OUTSTANDING_CREDIT_SQL = "SELECT COALESCE(sum(c.balance_xaf), 0) FROM public.tenant_credits c "
            + "JOIN public.tenants t ON t.id = c.tenant_id WHERE " + IN_SCOPE;

    static final String PAYMENTS_SQL =
            "SELECT p.tenant_id, p.amount_xaf, p.paid_at, p.period_start, p.period_end, p.plan_code, p.cycle_code, "
            + "p.credit_xaf "
            + "FROM public.tenant_payments p JOIN public.tenants t ON t.id = p.tenant_id WHERE " + IN_SCOPE
            + " AND p.voided_at IS NULL"; // a voided payment (9-2 correction) never counts

    /** Story 19.7a: the plan kinds are joined from the catalogue (set-based, no per-row lookup). */
    static final String EVENTS_SQL =
            "SELECT e.tenant_id, e.from_plan, e.to_plan, fp.kind AS from_kind, tp.kind AS to_kind, e.cause, "
            + "e.occurred_at "
            + "FROM public.tenant_plan_events e JOIN public.tenants t ON t.id = e.tenant_id "
            + "LEFT JOIN public.plans fp ON fp.code = e.from_plan LEFT JOIN public.plans tp ON tp.code = e.to_plan "
            + "WHERE " + IN_SCOPE
            + " ORDER BY e.occurred_at";

    static final String PLAN_TENANTS_SQL =
            "SELECT id, schema_name FROM public.tenants "
            + "WHERE status IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING') AND id <> '" + SYSTEM_TENANT_ID + "'::uuid";

    /**
     * Story 19.7a: the kind of the current plan (null for a code the catalogue does not know: not counted). One branch
     * of the 19.11c union: the tenant id literal, then the schema.
     */
    static final String CURRENT_PLAN_SQL =
            "SELECT %s::text AS tenant_id, (SELECT p.kind FROM \"%s\".subscriptions s "
            + "LEFT JOIN public.plans p ON p.code = s.plan_type ORDER BY s.created_at DESC LIMIT 1) AS kind";

    /** One branch of the 19.11c churn union: the tenant id literal, then the schema; binds the window bounds. */
    static final String GMV_SQL =
            "SELECT %s::text AS tenant_id, COALESCE(SUM(total_amount), 0) AS gmv FROM \"%s\".sales "
            + "WHERE status = 'COMPLETED' "
            + "AND COALESCE(occurred_at, created_at) >= ? AND COALESCE(occurred_at, created_at) < ?";

    static final String UNKNOWN_PLAN = com.keevo.admin.AdminLabels.UNKNOWN_PLAN;
    static final String FREE_FORM_CYCLE = com.keevo.admin.AdminLabels.FREE_FORM_CYCLE;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PlanCatalog catalog;
    /** Story 21.10 — labels and order of the billing cycles (owner: subscription.offer); null = no cycle known. */
    private final OfferAdministration offers;
    private final CrossTenantSql crossTenant;

    /** With the seeded plan catalogue and no billing cycle (tests). */
    public AdminRevenueService(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, PlanCatalog.SEED, null);
    }

    /** With cross-tenant statements of the default chunk size (tests). */
    public AdminRevenueService(JdbcTemplate jdbc, Clock clock, PlanCatalog catalog, OfferAdministration offers) {
        this(jdbc, clock, catalog, offers, new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminRevenueService(JdbcTemplate jdbc, Clock clock, PlanCatalog catalog, OfferAdministration offers,
                               CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.catalog = catalog;
        this.offers = offers;
        this.crossTenant = crossTenant;
    }

    // ── By plan (Story 21.10) ─────────────────────────────────────────────────

    /**
     * MRR and paying tenants today, revenue collected over the range (default current month), split per plan ×
     * billing cycle of the payments, with French labels; legacy payments without plan → « Plan non précisé », without
     * cycle → « Durée libre ». Rows in catalogue order (plan display order, then cycle order, unknowns last). Cost: the
     * payments and events queries of the summary + one cycles read (offer port); plan labels from the catalogue
     * cache; sorting with precomputed positions.
     */
    public List<PlanCycleRevenue> getByPlan(RevenueRangeQuery query) {
        LocalDate today = today();
        LocalDate[] range = resolveRange(query, today);
        Map<List<String>, RevenueMetrics.Totals> totals = loadMetrics().byPlanAndCycle(today, range[0], range[1]);
        Map<String, String> cycleLabels = new LinkedHashMap<>();
        if (offers != null) {
            offers.cycles().forEach(c -> cycleLabels.put(c.code(), c.label()));
        }
        Map<String, Integer> cycleOrder = positions(new ArrayList<>(cycleLabels.keySet()));
        Map<String, Integer> planOrder = positions(catalog.all().stream().map(p -> p.code().code()).toList());
        List<PlanCycleRevenue> rows = new ArrayList<>();
        totals.forEach((key, t) -> {
            String plan = key.get(0);
            String cycle = key.get(1);
            rows.add(new PlanCycleRevenue(plan, plan == null ? UNKNOWN_PLAN : catalog.label(plan, UNKNOWN_PLAN),
                    cycle, cycle == null ? FREE_FORM_CYCLE : cycleLabels.getOrDefault(cycle, FREE_FORM_CYCLE),
                    t.mrr(), t.paying(), t.collected(), t.payments()));
        });
        rows.sort(Comparator.comparingInt((PlanCycleRevenue r) -> rank(planOrder, r.planCode()))
                .thenComparingInt(r -> rank(cycleOrder, r.cycleCode())));
        return rows;
    }

    private static Map<String, Integer> positions(List<String> codes) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            positions.put(codes.get(i), i);
        }
        return positions;
    }

    private static int rank(Map<String, Integer> positions, String code) {
        return code == null ? Integer.MAX_VALUE : positions.getOrDefault(code, Integer.MAX_VALUE);
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    @Override
    public RevenueSummary getSummary(RevenueRangeQuery query) {
        LocalDate today = today();
        LocalDate[] range = resolveRange(query, today);
        LocalDate from = range[0];
        LocalDate to = range[1];

        RevenueMetrics metrics = loadMetrics();
        long mrr = metrics.mrrAt(today);
        long previous = metrics.mrrAt(YearMonth.from(today).minusMonths(1).atEndOfMonth());
        int churned = metrics.churned(from, to).size();
        int churnBase = metrics.churnBase(from, to);
        RevenueMetrics.Conversion conversion = metrics.conversion(from, to);
        PlanCounts plans = countCurrentPlans();
        Set<UUID> payingToday = metrics.payingTenantsAt(today);
        Long[] packs = packSales(from, to);
        int premiumWithoutPayment = (int) plans.premiumTenantIds().stream()
                .filter(id -> !payingToday.contains(id)).count();

        return new RevenueSummary(
                from, to,
                mrr, mrr * 12, previous, trendPercent(mrr, previous),
                payingToday.size(), plans.free(), plans.trial(),
                plans.premiumTenantIds().size(), premiumWithoutPayment,
                metrics.newPayingTenants(from, to).size(),
                churned, churnBase, RevenueMetrics.rate(churned, churnBase),
                conversion.converted(), conversion.trialsEnded(), conversion.rate(),
                metrics.revenueCollected(from, to), Math.toIntExact(packs[0]), packs[1], outstandingCredit());
    }

    /** Story 24.5 — the account credit held today by the live companies (XAF). */
    private long outstandingCredit() {
        Long total = jdbc.queryForObject(OUTSTANDING_CREDIT_SQL, Long.class);
        return total == null ? 0 : total;
    }

    /** Story 21.3 — {count, cash collected} of the usage packs paid between two WAT dates (inclusive). */
    private Long[] packSales(LocalDate from, LocalDate to) {
        Long[] sales = jdbc.queryForObject(PACK_SALES_SQL, (rs, i) -> new Long[] {rs.getLong(1), rs.getLong(2)},
                Timestamp.from(from.atStartOfDay(WAT).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant()));
        return sales == null ? new Long[] {0L, 0L} : sales;
    }

    // ── Trend ─────────────────────────────────────────────────────────────────

    @Override
    public List<RevenueTrendPoint> getTrend() {
        LocalDate today = today();
        YearMonth current = YearMonth.from(today);
        RevenueMetrics metrics = loadMetrics();

        List<RevenueTrendPoint> points = new ArrayList<>(TREND_MONTHS);
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth month = current.minusMonths(i);
            LocalDate first = month.atDay(1);
            LocalDate last = i == 0 ? today : month.atEndOfMonth();
            points.add(new RevenueTrendPoint(month,
                    metrics.mrrAt(last), metrics.payingAt(last),
                    metrics.newPayingTenants(first, last).size(),
                    metrics.churned(first, last).size()));
        }
        return points;
    }

    // ── Churn ─────────────────────────────────────────────────────────────────

    @Override
    public ChurnReport getChurn(String month) {
        YearMonth ym = resolveMonth(month);
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();

        RevenueMetrics metrics = loadMetrics();
        Map<UUID, PlanChange> churned = metrics.churned(first, last);
        int base = metrics.churnBase(first, last);
        Map<UUID, TenantRef> tenants = loadTenants(churned.keySet());
        Map<UUID, Long> gmv = gmvBefore(churned, tenants);

        List<ChurnedTenant> rows = new ArrayList<>();
        churned.forEach((tenantId, event) -> {
            TenantRef tenant = tenants.get(tenantId);
            LocalDate endDate = RevenueMetrics.planEndDate(event);
            Double tenure = metrics.streakStart(tenantId, endDate)
                    .map(start -> RevenueMetrics.monthsBetween(start, endDate))
                    .orElse(null);
            rows.add(new ChurnedTenant(tenantId, tenant == null ? null : tenant.name(), endDate, tenure,
                    gmv.getOrDefault(tenantId, 0L)));
        });
        rows.sort(Comparator.comparing(ChurnedTenant::planEndDate)
                .thenComparing(ChurnedTenant::tenantName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        return new ChurnReport(ym, base, RevenueMetrics.rate(rows.size(), base), List.copyOf(rows));
    }

    // ── Parameter validation ──────────────────────────────────────────────────

    /** Inclusive [from, to]; both absent → current month. */
    LocalDate[] resolveRange(RevenueRangeQuery query, LocalDate today) {
        String rawFrom = query == null ? null : blankToNull(query.from());
        String rawTo = query == null ? null : blankToNull(query.to());
        if (rawFrom == null && rawTo == null) {
            YearMonth current = YearMonth.from(today);
            return new LocalDate[]{current.atDay(1), current.atEndOfMonth()};
        }
        if (rawFrom == null || rawTo == null) {
            throw validation("Indiquez les deux dates de la période");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(rawFrom);
            to = LocalDate.parse(rawTo);
        } catch (DateTimeParseException e) {
            throw validation("Dates invalides (format AAAA-MM-JJ)");
        }
        checkYear(from.getYear());
        checkYear(to.getYear());
        if (from.isAfter(to)) {
            throw validation("La date de début doit précéder la date de fin");
        }
        if (!to.isBefore(from.plusMonths(MAX_RANGE_MONTHS))) {
            throw validation("La période ne peut pas dépasser " + MAX_RANGE_MONTHS + " mois");
        }
        return new LocalDate[]{from, to};
    }

    YearMonth resolveMonth(String month) {
        String raw = blankToNull(month);
        if (raw == null) return YearMonth.from(today());
        if (!MONTH_PATTERN.matcher(raw).matches()) {
            throw validation("Mois invalide (format AAAA-MM)");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            throw validation("Mois invalide (format AAAA-MM)");
        }
        checkYear(ym.getYear());
        return ym;
    }

    private static void checkYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw validation("Les dates doivent être comprises entre " + MIN_YEAR + " et " + MAX_YEAR);
        }
    }

    private static DomainException validation(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.now(clock.withZone(WAT));
    }

    RevenueMetrics loadMetrics() {
        List<RevenuePayment> payments = jdbc.query(PAYMENTS_SQL, (rs, i) -> new RevenuePayment(
                UUID.fromString(rs.getString("tenant_id")),
                rs.getLong("amount_xaf"),
                rs.getTimestamp("paid_at").toInstant(),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getString("plan_code"),
                rs.getString("cycle_code"),
                rs.getLong("credit_xaf")));
        List<PlanChange> events = jdbc.query(EVENTS_SQL, (rs, i) -> new PlanChange(
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("from_plan"),
                rs.getString("to_plan"),
                kind(rs.getString("from_kind")),
                kind(rs.getString("to_kind")),
                rs.getString("cause"),
                rs.getTimestamp("occurred_at").toInstant()));
        return new RevenueMetrics(payments, events);
    }

    private static PlanKind kind(String kind) {
        return kind != null ? PlanKind.valueOf(kind) : null;
    }

    private record TenantRef(UUID id, String name, String schemaName) {}

    /** One batched lookup of the churned tenants' name and schema. */
    private Map<UUID, TenantRef> loadTenants(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<UUID, TenantRef> result = new HashMap<>();
        jdbc.query("SELECT id, name, schema_name FROM public.tenants WHERE id IN (" + placeholders + ")",
                rs -> {
                    UUID id = UUID.fromString(rs.getString("id"));
                    result.put(id, new TenantRef(id, rs.getString("name"), rs.getString("schema_name")));
                },
                ids.toArray());
        return result;
    }

    /** Current plans read from each tenant schema's subscription (source of truth). */
    record PlanCounts(int free, int trial, Set<UUID> premiumTenantIds) {}

    PlanCounts countCurrentPlans() {
        List<TenantBranch> tenants = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(PLAN_TENANTS_SQL)) {
            String id = String.valueOf(row.get("id"));
            String schema = (String) row.get("schema_name");
            if (!TenantSchema.isValid(schema)) {
                log.warn("Revenue dashboard: tenant {} skipped (invalid schema name)", id);
                continue;
            }
            tenants.add(new TenantBranch(id, null, schema, null, null));
        }
        int free = 0;
        int trial = 0;
        Set<UUID> premium = new HashSet<>();
        // 19.11c: one presence query + one union per 100 tenants; a tenant without subscriptions table is not counted.
        List<TenantBranch> withPlans = crossTenant.withPresence(tenants).stream()
                .filter(t -> t.has(TenantTable.SUBSCRIPTIONS)).toList();
        for (String[] row : crossTenant.query(withPlans,
                (tenant, params) -> {
                    String schema = TenantSchema.validate(tenant.schemaName());
                    return String.format(CURRENT_PLAN_SQL, CrossTenantSql.tenantLiteral(tenant), schema);
                },
                union -> "SELECT * FROM (" + union + ") u",
                (rs, n) -> new String[]{rs.getString("tenant_id"), rs.getString("kind")})) {
            if (row[1] == null) continue; // no subscription row, or a code the catalogue does not know
            PlanKind kind = kind(row[1]); // 19.7a: by kind
            if (kind == PlanKind.FREE) free++;
            else if (kind == PlanKind.TRIAL) trial++;
            else if (kind == PlanKind.PAID) premium.add(UUID.fromString(row[0]));
        }
        return new PlanCounts(free, trial, premium);
    }

    /**
     * COMPLETED sales of the {@value #GMV_WINDOW_DAYS} WAT days ending on each churned tenant's plan end date
     * (inclusive), by tenant id; a tenant absent (unknown, no sales table, unreadable) reads 0. One presence query and
     * one union per 100 churned tenants, each branch with its own window.
     */
    private Map<UUID, Long> gmvBefore(Map<UUID, PlanChange> churned, Map<UUID, TenantRef> tenants) {
        Map<String, LocalDate> endDates = new HashMap<>();
        List<TenantBranch> branches = new ArrayList<>();
        churned.forEach((tenantId, event) -> {
            TenantRef tenant = tenants.get(tenantId);
            if (tenant == null || !TenantSchema.isValid(tenant.schemaName())) return;
            endDates.put(tenantId.toString(), RevenueMetrics.planEndDate(event));
            branches.add(new TenantBranch(tenantId.toString(), tenant.name(), tenant.schemaName(), null, null));
        });
        List<TenantBranch> withSales = crossTenant.withPresence(branches).stream()
                .filter(t -> t.has(TenantTable.SALES_CORE)).toList();
        Map<UUID, Long> out = new HashMap<>();
        crossTenant.query(withSales, (tenant, params) -> {
                    LocalDate planEndDate = endDates.get(tenant.id());
                    params.add(Timestamp.from(planEndDate.minusDays(GMV_WINDOW_DAYS - 1L).atStartOfDay(WAT).toInstant()));
                    params.add(Timestamp.from(planEndDate.plusDays(1).atStartOfDay(WAT).toInstant()));
                    String schema = TenantSchema.validate(tenant.schemaName());
                    return String.format(GMV_SQL, CrossTenantSql.tenantLiteral(tenant), schema);
                }, union -> "SELECT * FROM (" + union + ") u",
                (rs, n) -> Map.entry(UUID.fromString(rs.getString("tenant_id")), rs.getLong("gmv")))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    static Double trendPercent(long current, long previous) {
        if (previous <= 0) return null;
        return BigDecimal.valueOf((current - previous) * 100.0 / previous)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
