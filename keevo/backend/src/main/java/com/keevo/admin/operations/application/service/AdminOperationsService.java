package com.keevo.admin.operations.application.service;

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
 *   <li>GMV = {@code SUM(total_amount)} of {@code sales} with {@code status = 'COMPLETED'}; orders = their count.</li>
 *   <li>The period filters {@code COALESCE(occurred_at, created_at)}; day boundaries are Africa/Douala (WAT).</li>
 *   <li>Last activity = MAX(latest sale time, latest {@code audit_log.occurred_at}); a tenant is inactive when
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

    /** Sale time used for the period and for last activity. */
    private static final String SALE_TIME = "COALESCE(occurred_at, created_at)";
    private static final String IN_WINDOW =
            "status = 'COMPLETED' AND " + SALE_TIME + " >= ? AND " + SALE_TIME + " < ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminOperationsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ── GetOperationsOverviewUseCase ──────────────────────────────────────────

    @Override
    public OperationsOverview getOverview(OperationsPeriodQuery query) {
        Window window = resolveWindow(query);
        long totalGmv = 0;
        long totalOrders = 0;
        int activeTenants = 0;
        int newTenants = 0;

        for (TenantRef tenant : loadActiveTenants()) {
            if (!TenantSchema.isValid(tenant.schemaName())) { skipInvalid(tenant); continue; }
            Map<String, Object> row = safeSingleRow(tenant,
                    "SELECT COALESCE(SUM(total_amount), 0) AS gmv, COUNT(*) AS order_count "
                    + "FROM " + tenant.schemaName() + ".sales WHERE " + IN_WINDOW,
                    window.startTs(), window.endTs());
            if (row == null) continue; // skipped tenant: not counted anywhere, new tenants included
            if (tenant.createdAt() != null
                    && !tenant.createdAt().isBefore(window.start())
                    && tenant.createdAt().isBefore(window.end())) {
                newTenants++;
            }
            long orders = asLong(row.get("order_count"));
            totalGmv += asLong(row.get("gmv"));
            totalOrders += orders;
            if (orders > 0) activeTenants++;
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

        for (TenantRef tenant : loadActiveTenants()) {
            if (!TenantSchema.isValid(tenant.schemaName())) { skipInvalid(tenant); continue; }
            List<Map<String, Object>> rows = safeRows(tenant,
                    "SELECT CAST((" + SALE_TIME + ") AT TIME ZONE '" + WAT.getId() + "' AS DATE) AS day, "
                    + "COALESCE(SUM(total_amount), 0) AS gmv, COUNT(*) AS order_count "
                    + "FROM " + tenant.schemaName() + ".sales WHERE " + IN_WINDOW + " GROUP BY 1",
                    window.startTs(), window.endTs());
            for (Map<String, Object> row : rows) {
                LocalDate day = asLocalDate(row.get("day"));
                long[] acc = day != null ? byDay.get(day) : null;
                if (acc == null) continue;
                acc[0] += asLong(row.get("gmv"));
                acc[1] += asLong(row.get("order_count"));
            }
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
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }

        List<TenantActivity> rows = new ArrayList<>();
        for (TenantRef tenant : loadActiveTenants()) {
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // loadActiveTenants() filters; re-checked at use
            Map<String, Object> row = safeSingleRow(tenant,
                    "WITH w AS (SELECT COALESCE(SUM(total_amount), 0) AS gmv, COUNT(*) AS order_count "
                    + "FROM " + s + ".sales WHERE " + IN_WINDOW + ") "
                    + "SELECT w.gmv, w.order_count, "
                    + "(SELECT MAX(" + SALE_TIME + ") FROM " + s + ".sales) AS last_sale_at, "
                    + "(SELECT MAX(occurred_at) FROM " + s + ".audit_log) AS last_audit_at, "
                    + "(SELECT COUNT(*) FROM " + s + ".stores WHERE is_active = true AND type = 'STORE') AS store_count, "
                    + "(SELECT plan_type FROM " + s + ".subscriptions ORDER BY created_at DESC LIMIT 1) AS plan "
                    + "FROM w",
                    window.startTs(), window.endTs());
            if (row == null) continue;
            rows.add(new TenantActivity(
                    tenant.id(), tenant.name(), planOf(row, tenant),
                    asLong(row.get("gmv")), asLong(row.get("order_count")),
                    latest(asInstant(row.get("last_sale_at")), asInstant(row.get("last_audit_at"))),
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
                    "SELECT (SELECT MAX(" + SALE_TIME + ") FROM " + s + ".sales) AS last_sale_at, "
                    + "(SELECT MAX(occurred_at) FROM " + s + ".audit_log) AS last_audit_at, "
                    + "(SELECT plan_type FROM " + s + ".subscriptions ORDER BY created_at DESC LIMIT 1) AS plan");
            if (row == null) continue;
            Instant last = latest(asInstant(row.get("last_sale_at")), asInstant(row.get("last_audit_at")));
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
            default -> throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "period must be one of TODAY, 7D, 30D, 90D, CUSTOM");
        };
    }

    private Window customWindow(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from and to are required for a CUSTOM period");
        }
        LocalDate fromDay;
        LocalDate toDay;
        try {
            fromDay = LocalDate.parse(from.trim());
            toDay = LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from and to must be ISO dates (yyyy-MM-dd)");
        }
        if (fromDay.getYear() < MIN_YEAR || fromDay.getYear() > MAX_YEAR
                || toDay.getYear() < MIN_YEAR || toDay.getYear() > MAX_YEAR) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "from and to must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
        if (fromDay.isAfter(toDay)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from must be before or equal to to");
        }
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > MAX_CUSTOM_DAYS) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "a CUSTOM period cannot exceed " + MAX_CUSTOM_DAYS + " days");
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

    private List<Map<String, Object>> safeRows(TenantRef tenant, String sql, Object... args) {
        List<Map<String, Object>> rows = safeRowsOrNull(tenant, sql, args);
        return rows == null ? List.of() : rows;
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

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
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

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }
}
