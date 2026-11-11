package com.keevo.admin.operations.application.service;

import com.keevo.admin.TenantLastActivitySql;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.admin.operations.domain.model.*;
import com.keevo.admin.operations.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>The period filters the revenue instant (sale {@code COALESCE(occurred_at, created_at)}, invoice civil issue date); day boundaries
 *       are Africa/Douala (WAT).</li>
 *   <li>Last activity = latest merchant event ({@link com.keevo.admin.TenantLastActivitySql}); a tenant is inactive when
 *       it is null or older than {@value #INACTIVITY_DAYS} days.</li>
 *   <li>Only {@code public.tenants} with {@code status = 'ACTIVE'}, never the KV-ADMIN system tenant.</li>
 * </ul>
 *
 * <p>Story 19.11b (FUS-NFR11): never one statement per tenant. Tenants come from one {@code public.tenants} query,
 * their table presence from one catalogue query ({@link CrossTenantSql#withPresence}) and the per-tenant figures
 * (last activity, store count, plan) from chunked {@code UNION ALL} statements ({@link CrossTenantSql#query}); the
 * activity table sorts every tenant on the revenue totals and enriches only the tenants of the requested page.
 * Schema names are gated by {@link TenantSchema} before interpolation; a table absent from a schema reads as
 * {@code NULL} / 0. Deliberately NOT {@code @Transactional}: a chunk failing on one tenant's schema is retried tenant
 * by tenant and only that tenant loses its figures; connection / resource failures fail the request.
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
    private final CrossTenantSql crossTenant;

    public AdminOperationsService(JdbcTemplate jdbc, Clock clock, PlatformRevenuePort revenue,
                                  CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.revenue = revenue;
        this.crossTenant = crossTenant;
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

        List<TenantRef> tenants = loadActiveTenants();
        // 20.13 (R4): GMV and orders from the single revenue source, chunked statements for all tenants.
        Map<String, PlatformRevenuePort.Totals> byTenant =
                revenue.totals(branches(tenants), window.start(), window.end(), PlatformRevenuePort.Bucket.TENANT);
        // 19.11b: the order needs only public data + revenue, so every tenant is ranked here and only the tenants
        // of the requested page are read in their schemas (one presence query + one union of ≤ 100 branches).
        List<TenantRef> ranked = new ArrayList<>(tenants);
        Comparator<TenantRef> byGmv = Comparator.comparingLong(
                (TenantRef t) -> byTenant.getOrDefault(t.id(), PlatformRevenuePort.Totals.ZERO).revenue()).reversed();
        // Tenant id last: equal GMV and name keep one order across requests (no row repeated or lost between pages).
        ranked.sort(byGmv.thenComparing(TenantRef::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(TenantRef::id));

        int total = ranked.size();
        int fromIndex = (int) Math.min((long) pageNumber * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        int totalPages = (total + size - 1) / size;
        List<TenantRef> pageTenants = ranked.subList(fromIndex, toIndex);
        Map<String, TenantFigures> figures = figures(pageTenants, null);

        List<TenantActivity> rows = new ArrayList<>(pageTenants.size());
        for (TenantRef tenant : pageTenants) {
            TenantFigures f = figures.getOrDefault(tenant.id(), TenantFigures.NONE);
            PlatformRevenuePort.Totals totals = byTenant.getOrDefault(tenant.id(), PlatformRevenuePort.Totals.ZERO);
            rows.add(new TenantActivity(
                    tenant.id(), tenant.name(), f.plan() != null ? f.plan() : tenant.planType(),
                    totals.revenue(), totals.documents(), f.lastActivity(), f.storeCount()));
        }
        return new TenantActivityPage(List.copyOf(rows), total, totalPages, pageNumber);
    }

    // ── ListInactiveTenantsUseCase ────────────────────────────────────────────

    @Override
    public List<InactiveTenant> listInactiveTenants() {
        Instant now = clock.instant();
        Instant threshold = now.minus(INACTIVITY_DAYS, ChronoUnit.DAYS);
        List<InactiveTenant> result = new ArrayList<>();

        // 19.11b: last activity of every tenant in chunked unions; only the inactive ones come back.
        List<TenantRef> tenants = loadActiveTenants();
        Map<String, TenantFigures> figures = figures(tenants, threshold);
        for (TenantRef tenant : tenants) {
            TenantFigures f = figures.get(tenant.id());
            if (f == null) continue; // active within the threshold, or its schema could not be read
            Instant last = f.lastActivity();
            if (last != null && !last.isBefore(threshold)) continue; // filtered in SQL; re-checked here
            Long days = last == null ? null : Math.max(0, ChronoUnit.DAYS.between(last, now));
            result.add(new InactiveTenant(tenant.id(), tenant.name(), f.plan() != null ? f.plan() : tenant.planType(),
                    last, days));
        }

        // Never active first, then the longest dormant, then by name.
        result.sort(Comparator.comparing(InactiveTenant::daysSinceLastActivity,
                        Comparator.nullsFirst(Comparator.<Long>reverseOrder()))
                .thenComparing(InactiveTenant::tenantName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    /** One tenant's figures read in its schema; {@code NONE} when the schema could not be read. */
    private record TenantFigures(Instant lastActivity, int storeCount, String plan) {
        static final TenantFigures NONE = new TenantFigures(null, 0, null);
    }

    /**
     * Last activity, active STORE count and current plan of {@code tenants}, keyed by tenant id: one presence query
     * then ⌈T/100⌉ union statements. With {@code inactiveBefore}, only the tenants never active or last active
     * before it are returned (filtered in SQL). A tenant without any table (schema missing) is absent.
     */
    private Map<String, TenantFigures> figures(List<TenantRef> tenants, Instant inactiveBefore) {
        Map<String, TenantFigures> out = new HashMap<>();
        if (tenants.isEmpty()) return out;
        List<TenantBranch> present = crossTenant.withPresence(branches(tenants)).stream()
                .filter(t -> !t.tables().isEmpty()).toList();
        String filter = inactiveBefore == null ? ""
                : " WHERE last_activity_at IS NULL OR last_activity_at < CAST('"
                + inactiveBefore.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC) + "' AS timestamptz)";
        crossTenant.query(present, (tenant, params) -> {
            String s = TenantSchema.validate(tenant.schemaName());
            String activity = TenantLastActivitySql.expression(tenant, params);
            String stores = tenant.has(TenantTable.STORES)
                    ? "(SELECT COUNT(*) FROM " + s + ".stores WHERE is_active = true AND type = 'STORE')" : "0";
            String plan = tenant.has(TenantTable.SUBSCRIPTIONS)
                    ? "(SELECT plan_type FROM " + s + ".subscriptions ORDER BY created_at DESC LIMIT 1)"
                    : "CAST(NULL AS text)";
            return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, " + activity
                    + " AS last_activity_at, CAST(" + stores + " AS bigint) AS store_count, CAST(" + plan
                    + " AS text) AS plan";
        }, union -> "SELECT * FROM (" + union + ") u" + filter, (rs, n) -> {
            Timestamp last = rs.getTimestamp("last_activity_at");
            return Map.entry(rs.getString("tenant_id"), new TenantFigures(last == null ? null : last.toInstant(),
                    (int) rs.getLong("store_count"), rs.getString("plan")));
        }).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
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

    private static Instant asInstant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i) return i;
        return null;
    }
}
