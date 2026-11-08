package com.keevo.admin.operations.application.service;

import com.keevo.admin.TenantLastActivitySql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.operations.domain.model.*;
import com.keevo.admin.operations.domain.port.in.*;
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

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * AdminOperationsService — cross-tenant operational activity aggregator (Story 9.7).
 *
 * <p>Definitions (PO defaults written in the story, kept):
 * <ul>
 *   <li>GMV = the single revenue source of 20.9 ({@link PlatformRevenuePort}, Story 20.13: COMPLETED sales ∪ counted
 *       invoices − credit notes, a bridged sale counted once); orders = its revenue documents.</li>
 *   <li>The period filters the revenue instant (sale {@code occurred_at}, invoice civil issue date); day boundaries
 *       are Africa/Douala (WAT).</li>
 *   <li>Last activity = latest merchant event ({@link com.keevo.admin.TenantLastActivitySql}); a tenant is inactive when
 *       it is null or older than {@value #INACTIVITY_DAYS} days.</li>
 *   <li>Only {@code public.tenants} with {@code status = 'ACTIVE'}, never the KV-ADMIN system tenant.</li>
 * </ul>
 *
 * <p>Uses raw JdbcTemplate with schema-qualified SQL, one aggregated query per tenant schema per endpoint.
 * Schema names come from {@code public.tenants.schema_name} and are gated by {@link TenantSchema#isValid}
 * before interpolation. Deliberately NOT {@code @Transactional}: each statement runs on its own, so a tenant
 * whose schema query fails (missing table, broken schema) is skipped without aborting the queries of the
 * other tenants (PostgreSQL aborts a whole transaction after a failed statement).
 */
@Service
public class AdminOperationsService implements GetOperationsOverviewUseCase, GetGmvTrendUseCase,
        ListTenantActivityUseCase, ListInactiveTenantsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminOperationsService.class);

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final int INACTIVITY_DAYS = 14;
    static final int MAX_CUSTOM_DAYS = 366;
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;
    /** CUSTOM dates outside this range are rejected (out-of-range dates would fail every tenant query). */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 9999;

    private static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PlatformRevenuePort revenue;

    public AdminOperationsService(JdbcTemplate jdbc, Clock clock, PlatformRevenuePort revenue) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.revenue = revenue;
    }

    // ── GetOperationsOverviewUseCase ──────────────────────────────────────────

    @Override
    public OperationsOverview getOverview(OperationsPeriodQuery query) {
        Window window = resolveWindow(query);
        long totalGmv = 0;
        long totalOrders = 0;
        int activeTenants = 0;
        int newTenants = 0;

        // 20.13 (R4): GMV and orders from the single revenue source, one statement for all tenants.
        List<TenantRef> tenants = loadActiveTenants();
        Map<String, PlatformRevenuePort.Totals> byTenant =
                revenue.totals(branches(tenants), window.start(), window.end(), PlatformRevenuePort.Bucket.TENANT);
        for (TenantRef tenant : tenants) {
            if (tenant.createdAt() != null
                    && !tenant.createdAt().isBefore(window.start())
                    && tenant.createdAt().isBefore(window.end())) {
                newTenants++;
            }
            PlatformRevenuePort.Totals totals = byTenant.getOrDefault(tenant.id(), PlatformRevenuePort.Totals.ZERO);
            totalGmv += totals.revenue();
            totalOrders += totals.documents();
            if (totals.documents() > 0) activeTenants++;
        }
        return new OperationsOverview(totalGmv, totalOrders, activeTenants, newTenants, window.label());
    }

    // ── GetGmvTrendUseCase ────────────────────────────────────────────────────

    @Override
    public List<GmvTrendPoint> getTrend(OperationsPeriodQuery query) {
        Window window = resolveWindow(query);
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (LocalDate d = window.firstDay(); !d.isAfter(window.lastDay()); d = d.plusDays(1)) {
            byDay.put(d, new long[2]);
        }

        // 20.13 (R4): daily GMV and orders from the single revenue source, one statement.
        Map<String, PlatformRevenuePort.Totals> daily = revenue.totals(branches(loadActiveTenants()),
                window.start(), window.end(), PlatformRevenuePort.Bucket.DAY);
        for (Map.Entry<String, PlatformRevenuePort.Totals> day : daily.entrySet()) {
            long[] acc = byDay.get(LocalDate.parse(day.getKey()));
            if (acc == null) continue;
            acc[0] += day.getValue().revenue();
            acc[1] += day.getValue().documents();
        }
        return byDay.entrySet().stream()
                .map(e -> new GmvTrendPoint(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    // ── ListTenantActivityUseCase ─────────────────────────────────────────────

    @Override
    public TenantActivityPage listTenantActivity(OperationsPeriodQuery query, Integer page, Integer pageSize) {
        Window window = resolveWindow(query);
        int pageNumber = page == null ? 0 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (pageNumber < 0) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Le numéro de page doit être positif");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "La taille de page doit être comprise entre 1 et " + MAX_PAGE_SIZE);
        }

        List<TenantActivity> rows = new ArrayList<>();
        List<TenantRef> tenants = loadActiveTenants();
        // 20.13 (R4): GMV and orders from the single revenue source, one statement for all tenants.
        Map<String, PlatformRevenuePort.Totals> byTenant =
                revenue.totals(branches(tenants), window.start(), window.end(), PlatformRevenuePort.Bucket.TENANT);
        for (TenantRef tenant : tenants) {
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // loadActiveTenants() filters; re-checked at use
            Map<String, Object> row = safeSingleRow(tenant,
                    "SELECT " + TenantLastActivitySql.expression(s) + " AS last_activity_at, "
                    + "(SELECT COUNT(*) FROM " + s + ".stores WHERE is_active = true AND type = 'STORE') AS store_count, "
                    + "(SELECT plan_type FROM " + s + ".subscriptions ORDER BY created_at DESC LIMIT 1) AS plan",
                    TenantLastActivitySql.args(tenant.id()));
            if (row == null) continue;
            PlatformRevenuePort.Totals totals = byTenant.getOrDefault(tenant.id(), PlatformRevenuePort.Totals.ZERO);
            rows.add(new TenantActivity(
                    tenant.id(), tenant.name(), planOf(row, tenant),
                    totals.revenue(), totals.documents(),
                    asInstant(row.get("last_activity_at")),
                    (int) asLong(row.get("store_count"))));
        }

        rows.sort(Comparator.comparingLong(TenantActivity::gmv).reversed()
                .thenComparing(TenantActivity::tenantName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        int total = rows.size();
        int fromIndex = (int) Math.min((long) pageNumber * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        int totalPages = (total + size - 1) / size;
        return new TenantActivityPage(List.copyOf(rows.subList(fromIndex, toIndex)), total, totalPages, pageNumber);
    }

    // ── ListInactiveTenantsUseCase ────────────────────────────────────────────

    @Override
    public List<InactiveTenant> listInactiveTenants() {
        Instant now = clock.instant();
        Instant threshold = now.minus(INACTIVITY_DAYS, ChronoUnit.DAYS);
        List<InactiveTenant> result = new ArrayList<>();

        for (TenantRef tenant : loadActiveTenants()) {
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // loadActiveTenants() filters; re-checked at use
            Map<String, Object> row = safeSingleRow(tenant,
                    "SELECT " + TenantLastActivitySql.expression(s) + " AS last_activity_at, "
                    + "(SELECT plan_type FROM " + s + ".subscriptions ORDER BY created_at DESC LIMIT 1) AS plan",
                    TenantLastActivitySql.args(tenant.id()));
            if (row == null) continue;
            Instant last = asInstant(row.get("last_activity_at"));
            if (last != null && !last.isBefore(threshold)) continue;
            Long days = last == null ? null : Math.max(0, ChronoUnit.DAYS.between(last, now));
            result.add(new InactiveTenant(tenant.id(), tenant.name(), planOf(row, tenant), last, days));
        }

        // Never active first, then the longest dormant, then by name.
        result.sort(Comparator.comparing(InactiveTenant::daysSinceLastActivity,
                        Comparator.nullsFirst(Comparator.<Long>reverseOrder()))
                .thenComparing(InactiveTenant::tenantName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    // ── Period resolution ─────────────────────────────────────────────────────

    /** Half-open instant window [start, end) covering whole WAT days firstDay..lastDay. */
    record Window(LocalDate firstDay, LocalDate lastDay, Instant start, Instant end, String label) {
        // OffsetDateTime binds as timestamptz: no dependency on the JVM / session time zone.
        OffsetDateTime startTs() { return start.atOffset(ZoneOffset.UTC); }
        OffsetDateTime endTs()   { return end.atOffset(ZoneOffset.UTC); }
    }

    Window resolveWindow(OperationsPeriodQuery query) {
        String period = query == null || query.period() == null || query.period().isBlank()
                ? "7D" : query.period().trim().toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(clock.withZone(WAT));
        return switch (period) {
            case "TODAY" -> window(today, today, "Aujourd'hui");
            case "7D" -> window(today.minusDays(6), today, "7 derniers jours");
            case "30D" -> window(today.minusDays(29), today, "30 derniers jours");
            case "90D" -> window(today.minusDays(89), today, "90 derniers jours");
            case "CUSTOM" -> customWindow(query.from(), query.to());
            default -> throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                    "Période inconnue");
        };
    }

    private Window customWindow(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Indiquez les deux dates de la période personnalisée");
        }
        LocalDate fromDay;
        LocalDate toDay;
        try {
            fromDay = LocalDate.parse(from.trim());
            toDay = LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Dates invalides (format AAAA-MM-JJ)");
        }
        if (fromDay.getYear() < MIN_YEAR || fromDay.getYear() > MAX_YEAR
                || toDay.getYear() < MIN_YEAR || toDay.getYear() > MAX_YEAR) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                    "Les dates doivent être comprises entre " + MIN_YEAR + " et " + MAX_YEAR);
        }
        if (fromDay.isAfter(toDay)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "La date de début doit précéder la date de fin");
        }
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > MAX_CUSTOM_DAYS) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                    "Une période personnalisée ne peut pas dépasser " + MAX_CUSTOM_DAYS + " jours");
        }
        return window(fromDay, toDay, "Du " + fromDay.format(FR_DATE) + " au " + toDay.format(FR_DATE));
    }

    private static Window window(LocalDate firstDay, LocalDate lastDay, String label) {
        return new Window(firstDay, lastDay,
                firstDay.atStartOfDay(WAT).toInstant(),
                lastDay.plusDays(1).atStartOfDay(WAT).toInstant(),
                label);
    }

    // ── Tenant loading & JDBC helpers ─────────────────────────────────────────

    private record TenantRef(String id, String schemaName, String name, String planType, Instant createdAt) {}

    private static List<TenantBranch> branches(List<TenantRef> tenants) {
        return tenants.stream()
                .map(t -> new TenantBranch(t.id(), t.name(), t.schemaName(), "ACTIVE", t.createdAt()))
                .toList();
    }

    private static void skipInvalid(TenantRef tenant) {
        log.warn("Operations monitoring: tenant {} skipped (invalid schema name)", tenant.id());
    }

    private List<TenantRef> loadActiveTenants() {
        return jdbc.queryForList(
                        "SELECT id::text AS id, schema_name, name, plan_type, created_at FROM public.tenants "
                        + "WHERE id <> '" + SYSTEM_TENANT_ID + "'::uuid AND status = 'ACTIVE' ORDER BY name")
                .stream()
                .map(r -> new TenantRef(
                        (String) r.get("id"), (String) r.get("schema_name"), (String) r.get("name"),
                        (String) r.get("plan_type"), asInstant(r.get("created_at"))))
                .filter(t -> {
                    if (TenantSchema.isValid(t.schemaName())) return true;
                    // Tenant id only — never the (invalid) schema name.
                    log.warn("Operations monitoring: tenant {} skipped (invalid schema name)", t.id());
                    return false;
                })
                .toList();
    }

    /** First row of a per-tenant query, or null when the tenant's schema query fails (tenant skipped);
     *  connection / resource failures are rethrown. */
    private Map<String, Object> safeSingleRow(TenantRef tenant, String sql, Object... args) {
        List<Map<String, Object>> rows = safeRowsOrNull(tenant, sql, args);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> safeRowsOrNull(TenantRef tenant, String sql, Object... args) {
        try {
            return jdbc.queryForList(sql, args);
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            // Database outage / pool exhaustion (incl. CannotGetJdbcConnectionException): fail the request
            // instead of answering 200 with zeros.
            throw e;
        } catch (DataAccessException e) {
            // Schema / SQL / data error of this tenant only (missing table, broken schema…): skip it.
            // Tenant id only — never the schema name or SQL in logs.
            log.warn("Operations monitoring: tenant {} skipped ({})", tenant.id(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** Plan from the tenant-schema subscriptions table (source of truth), else public.tenants.plan_type. */
    private static String planOf(Map<String, Object> row, TenantRef tenant) {
        Object plan = row.get("plan");
        return plan != null ? plan.toString() : tenant.planType();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant asInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }
}
