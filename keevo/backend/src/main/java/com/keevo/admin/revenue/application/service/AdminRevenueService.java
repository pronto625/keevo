package com.keevo.admin.revenue.application.service;

import com.keevo.admin.revenue.domain.model.*;
import com.keevo.admin.revenue.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
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

    static final String PAYMENTS_SQL =
            "SELECT p.tenant_id, p.amount_xaf, p.paid_at, p.period_start, p.period_end "
            + "FROM public.tenant_payments p JOIN public.tenants t ON t.id = p.tenant_id WHERE " + IN_SCOPE;

    static final String EVENTS_SQL =
            "SELECT e.tenant_id, e.from_plan, e.to_plan, e.cause, e.occurred_at "
            + "FROM public.tenant_plan_events e JOIN public.tenants t ON t.id = e.tenant_id WHERE " + IN_SCOPE
            + " ORDER BY e.occurred_at";

    static final String PLAN_TENANTS_SQL =
            "SELECT id, schema_name FROM public.tenants "
            + "WHERE status IN ('ACTIVE', 'SUSPENDED', 'DELETION_PENDING') AND id <> '" + SYSTEM_TENANT_ID + "'::uuid";

    static final String CURRENT_PLAN_SQL =
            "SELECT plan_type FROM \"%s\".subscriptions ORDER BY created_at DESC LIMIT 1";

    static final String GMV_SQL =
            "SELECT COALESCE(SUM(total_amount), 0) FROM \"%s\".sales WHERE status = 'COMPLETED' "
            + "AND COALESCE(occurred_at, created_at) >= ? AND COALESCE(occurred_at, created_at) < ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminRevenueService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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
                metrics.revenueCollected(from, to));
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

        List<ChurnedTenant> rows = new ArrayList<>();
        churned.forEach((tenantId, event) -> {
            TenantRef tenant = tenants.get(tenantId);
            LocalDate endDate = RevenueMetrics.planEndDate(event);
            Double tenure = metrics.streakStart(tenantId, endDate)
                    .map(start -> RevenueMetrics.monthsBetween(start, endDate))
                    .orElse(null);
            rows.add(new ChurnedTenant(tenantId, tenant == null ? null : tenant.name(), endDate, tenure,
                    gmvBefore(tenantId, tenant, endDate)));
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
            throw validation("from et to sont requis ensemble");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(rawFrom);
            to = LocalDate.parse(rawTo);
        } catch (DateTimeParseException e) {
            throw validation("from et to doivent être des dates ISO (yyyy-MM-dd)");
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
            throw validation("month doit être au format YYYY-MM");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            throw validation("month doit être au format YYYY-MM");
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
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
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
                rs.getDate("period_end").toLocalDate()));
        List<PlanChange> events = jdbc.query(EVENTS_SQL, (rs, i) -> new PlanChange(
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("from_plan"),
                rs.getString("to_plan"),
                rs.getString("cause"),
                rs.getTimestamp("occurred_at").toInstant()));
        return new RevenueMetrics(payments, events);
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
        int free = 0;
        int trial = 0;
        Set<UUID> premium = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(PLAN_TENANTS_SQL)) {
            String id = String.valueOf(row.get("id"));
            String schema = (String) row.get("schema_name");
            if (!TenantSchema.isValid(schema)) {
                log.warn("Revenue dashboard: tenant {} skipped (invalid schema name)", id);
                continue;
            }
            List<String> plans = safeQuery(id, () ->
                    jdbc.queryForList(String.format(CURRENT_PLAN_SQL, schema), String.class));
            if (plans == null || plans.isEmpty()) continue;
            String plan = plans.get(0);
            if ("FREE".equals(plan)) free++;
            else if ("PREMIUM_TRIAL".equals(plan)) trial++;
            else if ("PREMIUM".equals(plan)) premium.add(UUID.fromString(id));
        }
        return new PlanCounts(free, trial, premium);
    }

    /**
     * COMPLETED sales of the {@value #GMV_WINDOW_DAYS} WAT days ending on {@code planEndDate} (inclusive);
     * 0 when unreadable.
     */
    private long gmvBefore(UUID tenantId, TenantRef tenant, LocalDate planEndDate) {
        if (tenant == null || !TenantSchema.isValid(tenant.schemaName())) return 0L;
        Timestamp start = Timestamp.from(
                planEndDate.minusDays(GMV_WINDOW_DAYS - 1L).atStartOfDay(WAT).toInstant());
        Timestamp end = Timestamp.from(planEndDate.plusDays(1).atStartOfDay(WAT).toInstant());
        Long gmv = safeQuery(tenantId.toString(), () ->
                jdbc.queryForObject(String.format(GMV_SQL, tenant.schemaName()), Long.class, start, end));
        return gmv == null ? 0L : gmv;
    }

    /** Runs a per-tenant query: null when the tenant's own schema/SQL fails, connection failures rethrown. */
    private <T> T safeQuery(String tenantId, java.util.function.Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            // Tenant id only — never the schema name or SQL in logs.
            log.warn("Revenue dashboard: tenant {} skipped ({})", tenantId, e.getClass().getSimpleName());
            return null;
        }
    }

    static Double trendPercent(long current, long previous) {
        if (previous <= 0) return null;
        return BigDecimal.valueOf((current - previous) * 100.0 / previous)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
