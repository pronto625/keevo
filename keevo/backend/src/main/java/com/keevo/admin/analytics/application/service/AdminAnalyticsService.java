package com.keevo.admin.analytics.application.service;

import com.keevo.admin.analytics.domain.model.*;
import com.keevo.admin.analytics.domain.port.in.*;
import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.platformrevenue.domain.port.out.PlatformRevenuePort;
import com.keevo.identity.api.OwnerRecipientResolver;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * AdminAnalyticsService — platform usage analytics for the super admin (Story 9-4, FR80).
 *
 * <p>Definitions:
 * <ul>
 *   <li>Active user = a row of {@code public.user_daily_activity} (written by {@code UserActivityRecorder}
 *       on authenticated API calls, SUPER_ADMIN excluded). DAU = distinct users today (WAT); MAU = distinct
 *       users over the last {@value #ACTIVE_WINDOW_DAYS} days, today included. Active tenant = ≥ 1 active
 *       user in the window.</li>
 *   <li>KV-ADMIN (system tenant) is excluded everywhere, including its users' activity. Current-state KPIs
 *       (total tenants, stores, products, DAU/MAU, adoption, geography, top stores) exclude {@code DELETED}
 *       tenants; history (growth new / cumulative tenants, retention cohorts, past months' MAU) counts every
 *       tenant ever created, DELETED included, so past figures never shrink after a deletion.</li>
 *   <li>GMV = the single revenue source of 20.9 ({@link PlatformRevenuePort}, Story 20.13 — COMPLETED sales ∪ counted
 *       invoices − credit notes, a bridged sale counted once); « completed sales » = its revenue documents (sales +
 *       counted invoices). Top stores stay sales-only (documents carry no store yet — 20.9 rule). Day / week / month
 *       boundaries are WAT.</li>
 *   <li>Geography = the owner phone's calling code (V1 proxy: stores have no structured city).</li>
 * </ul>
 *
 * <p>Cross-schema reads (Story 19.11b, FUS-NFR11): never one statement per tenant. The tenants come from one
 * {@code public.tenants} query, their table presence from one catalogue query ({@link CrossTenantSql#withPresence}),
 * and the per-tenant figures from chunked {@code UNION ALL} statements ({@link CrossTenantSql#query}, 100 tenants
 * per statement) aggregated or top-K-limited in SQL; a table absent from a schema (module never used) is never a
 * branch / reads as 0 or false. Schema names are gated by {@link TenantSchema} before interpolation. Not
 * {@code @Transactional}: a chunk failing on a tenant's own schema / SQL / data error is retried tenant by tenant
 * and only that tenant is skipped; connection / resource failures are rethrown (9-7 rule).
 */
@Service
public class AdminAnalyticsService implements GetAnalyticsOverviewUseCase, GetGrowthUseCase, GetGeographyUseCase,
        GetRetentionUseCase, GetTopStoresUseCase, GetFeatureAdoptionUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminAnalyticsService.class);

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final int ACTIVE_WINDOW_DAYS = 30;
    static final int DEFAULT_GROWTH_MONTHS = 12;
    static final int MAX_GROWTH_MONTHS = 24;
    static final int DEFAULT_RETENTION_WEEKS = 8;
    static final int MAX_RETENTION_WEEKS = 12;
    static final int COHORT_ROWS = 12;
    static final int TOP_STORES_LIMIT = 20;
    static final int DEFAULT_TOP_STORES_DAYS = 30;
    static final int MAX_RANGE_DAYS = 366;
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 9999;

    static final String UNNAMED_TENANT = "Sans nom";
    static final String COUNTRY_OTHER = "OTHER";
    static final String COUNTRY_UNKNOWN = "UNKNOWN";

    /** Calling code → ISO country (3-digit codes first; none is a prefix of another). */
    private static final Map<String, String> CALLING_CODES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("+237", "CM");
        m.put("+225", "CI");
        m.put("+221", "SN");
        m.put("+241", "GA");
        m.put("+242", "CG");
        m.put("+243", "CD");
        m.put("+229", "BJ");
        m.put("+228", "TG");
        m.put("+226", "BF");
        m.put("+223", "ML");
        m.put("+227", "NE");
        m.put("+235", "TD");
        m.put("+236", "CF");
        m.put("+240", "GQ");
        m.put("+33", "FR");
        CALLING_CODES = Collections.unmodifiableMap(m);
    }

    static final List<String> FEATURES = List.of(
            "SALES", "INVENTORY", "TRANSFERS", "WHATSAPP_REPORTS", "STOCK_ALERTS", "OFFLINE_SYNC");

    private static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";
    private static final String SALE_TIME = "COALESCE(occurred_at, created_at)";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final PlatformRevenuePort revenue;
    private final CrossTenantSql crossTenant;

    public AdminAnalyticsService(JdbcTemplate jdbc, Clock clock, OwnerRecipientResolver ownerRecipientResolver,
                                 PlatformRevenuePort revenue, CrossTenantSql crossTenant) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.revenue = revenue;
        this.crossTenant = crossTenant;
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    @Override
    public AnalyticsOverview getOverview() {
        LocalDate today = today();
        LocalDate windowStart = today.minusDays(ACTIVE_WINDOW_DAYS - 1);

        long dau = countDistinctUsers(today, today);
        long mau = countDistinctUsers(windowStart, today);
        Double ratio = mau == 0 ? null : (double) dau / mau;

        List<TenantRef> tenants = current(loadTenants());
        OffsetDateTime activeSince = startOf(windowStart);
        OffsetDateTime tomorrow = startOf(today.plusDays(1));
        // 19.11b: table presence once, then the store / product counts of every tenant in chunked unions.
        List<TenantBranch> present = crossTenant.withPresence(branches(queryable(tenants)));
        long stores = 0, activeStores = 0, products = 0;
        for (long[] chunk : crossTenant.query(
                present.stream().filter(t -> t.has(TenantTable.STORES) || t.has(TenantTable.PRODUCTS_ARCHIVED)).toList(),
                (tenant, params) -> overviewBranch(tenant, params, activeSince, tomorrow),
                union -> "SELECT COALESCE(SUM(store_count), 0), COALESCE(SUM(active_store_count), 0), "
                        + "COALESCE(SUM(product_count), 0) FROM (" + union + ") u",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)})) {
            stores += chunk[0];
            activeStores += chunk[1];
            products += chunk[2];
        }
        // 20.13 (R4): GMV and document count from the single revenue source — chunked statements for all tenants.
        PlatformRevenuePort.Totals all = revenue.totals(present, null, null, PlatformRevenuePort.Bucket.TENANT)
                .values().stream().reduce(PlatformRevenuePort.Totals.ZERO, PlatformRevenuePort.Totals::plus);
        return new AnalyticsOverview(dau, mau, ratio, tenants.size(), stores, activeStores, products,
                all.documents(), all.revenue());
    }

    /** One tenant's store / active store / product counts; a table absent from its schema counts 0. */
    private static String overviewBranch(TenantBranch tenant, List<Object> params, OffsetDateTime activeSince,
                                         OffsetDateTime tomorrow) {
        String s = TenantSchema.validate(tenant.schemaName());
        String stores = tenant.has(TenantTable.STORES)
                ? "(SELECT COUNT(*) FROM " + s + ".stores WHERE is_active = true AND type = 'STORE')" : "0";
        String active = "0";
        if (tenant.has(TenantTable.STORES, TenantTable.SALES_CORE)) {
            active = "(SELECT COUNT(*) FROM " + s + ".stores st WHERE st.is_active = true AND st.type = 'STORE' "
                    + "AND EXISTS (SELECT 1 FROM " + s + ".sales sa WHERE sa.store_id = st.id AND sa.status = 'COMPLETED' "
                    + "AND COALESCE(sa.occurred_at, sa.created_at) >= ? "
                    + "AND COALESCE(sa.occurred_at, sa.created_at) < ?))";
            params.add(activeSince);
            params.add(tomorrow);
        }
        String products = tenant.has(TenantTable.PRODUCTS_ARCHIVED)
                ? "(SELECT COUNT(*) FROM " + s + ".products WHERE archived = false)" : "0";
        return "SELECT CAST(" + stores + " AS bigint) AS store_count, CAST(" + active + " AS bigint) AS active_store_count, "
                + "CAST(" + products + " AS bigint) AS product_count";
    }

    private long countDistinctUsers(LocalDate from, LocalDate to) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT a.user_id) FROM public.user_daily_activity a "
                + "JOIN public.tenants t ON t.id = a.tenant_id "
                + "WHERE t.id <> '" + SYSTEM_TENANT_ID + "'::uuid AND t.status <> 'DELETED' "
                + "AND a.activity_date >= ? AND a.activity_date <= ?",
                Long.class, from, to);
        return count == null ? 0 : count;
    }

    // ── Growth ────────────────────────────────────────────────────────────────

    @Override
    public List<GrowthPoint> getGrowth(String months) {
        int count = boundedInt(months, "Le nombre de mois", DEFAULT_GROWTH_MONTHS, MAX_GROWTH_MONTHS);
        YearMonth current = YearMonth.from(today());
        YearMonth first = current.minusMonths(count - 1L);
        LocalDate firstDay = first.atDay(1);
        LocalDate endDay = current.plusMonths(1).atDay(1); // exclusive

        Map<YearMonth, long[]> sales = new TreeMap<>(); // [count, gmv]
        Map<YearMonth, Long> mau = new HashMap<>();
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) sales.put(m, new long[2]);

        // History: every tenant ever created (DELETED included) for new / cumulative counts; sales are read
        // from the schemas that still exist (a DELETED tenant's schema is dropped).
        List<TenantRef> tenants = loadTenants();
        // 20.13 (R4): monthly revenue and document count from the single revenue source, one statement.
        Map<String, PlatformRevenuePort.Totals> monthly = revenue.totals(branches(queryable(current(tenants))),
                startOf(firstDay).toInstant(), startOf(endDay).toInstant(), PlatformRevenuePort.Bucket.MONTH);
        for (Map.Entry<String, PlatformRevenuePort.Totals> month : monthly.entrySet()) {
            long[] acc = sales.get(parseMonth(month.getKey()));
            if (acc == null) continue;
            acc[0] += month.getValue().documents();
            acc[1] += month.getValue().revenue();
        }

        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT to_char(a.activity_date, 'YYYY-MM') AS month, COUNT(DISTINCT a.user_id) AS mau "
                + "FROM public.user_daily_activity a JOIN public.tenants t ON t.id = a.tenant_id "
                + "WHERE t.id <> '" + SYSTEM_TENANT_ID + "'::uuid "
                + "AND a.activity_date >= ? AND a.activity_date < ? GROUP BY 1",
                firstDay, endDay)) {
            YearMonth m = parseMonth(row.get("month"));
            if (m != null) mau.put(m, asLong(row.get("mau")));
        }

        List<GrowthPoint> points = new ArrayList<>();
        for (Map.Entry<YearMonth, long[]> e : sales.entrySet()) {
            YearMonth m = e.getKey();
            long created = 0, cumulative = 0;
            for (TenantRef t : tenants) {
                if (t.createdAt() == null) continue;
                YearMonth createdMonth = YearMonth.from(t.createdAt().atZone(WAT));
                if (createdMonth.equals(m)) created++;
                if (!createdMonth.isAfter(m)) cumulative++;
            }
            points.add(new GrowthPoint(m.toString(), created, cumulative,
                    e.getValue()[0], e.getValue()[1], mau.getOrDefault(m, 0L)));
        }
        return points;
    }

    // ── Geography ─────────────────────────────────────────────────────────────

    @Override
    public List<CountryCount> getGeography() {
        // 19.11b: every owner phone in one public query (identity api), never one lookup per tenant. That query reads
        // public tables only: a failure there is a database failure and fails the screen (no per-tenant skip left).
        List<TenantRef> tenants = queryable(current(loadTenants()));
        Map<String, String> phones = ownerRecipientResolver.resolveOwnerWhatsAppPhones(
                tenants.stream().map(TenantRef::schemaName).toList());
        Map<String, Long> byCountry = new HashMap<>();
        for (TenantRef tenant : tenants) {
            byCountry.merge(countryOf(phones.get(tenant.schemaName())), 1L, Long::sum);
        }
        return byCountry.entrySet().stream()
                .map(e -> new CountryCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CountryCount::tenantCount).reversed()
                        .thenComparing(CountryCount::country))
                .toList();
    }

    /** {@code +237…} → {@code CM}; unknown calling code → {@code OTHER}; no phone → {@code UNKNOWN}. */
    static String countryOf(String phone) {
        if (phone == null || phone.isBlank()) return COUNTRY_UNKNOWN;
        String p = phone.trim().replaceAll("[\\s.\\-()]", "");
        if (p.startsWith("00")) {
            p = "+" + p.substring(2);
        } else if (!p.startsWith("+") && p.length() >= 11 && p.chars().allMatch(Character::isDigit)) {
            // Bare international digits (e.g. 237690000000): only when they start with a known code.
            String withPlus = "+" + p;
            if (CALLING_CODES.keySet().stream().anyMatch(withPlus::startsWith)) p = withPlus;
        }
        for (Map.Entry<String, String> e : CALLING_CODES.entrySet()) {
            if (p.startsWith(e.getKey())) return e.getValue();
        }
        return COUNTRY_OTHER;
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    @Override
    public RetentionReport getRetention(String weeks) {
        int columns = boundedInt(weeks, "Le nombre de semaines", DEFAULT_RETENTION_WEEKS, MAX_RETENTION_WEEKS);
        LocalDate today = today();
        LocalDate currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate oldestCohort = currentWeek.minusWeeks(COHORT_ROWS - 1L);

        Map<LocalDate, Set<String>> cohorts = new LinkedHashMap<>();
        for (int i = 0; i < COHORT_ROWS; i++) cohorts.put(currentWeek.minusWeeks(i), new HashSet<>());
        for (TenantRef t : loadTenants()) {
            if (t.createdAt() == null) continue;
            LocalDate week = mondayOf(t.createdAt().atZone(WAT).toLocalDate());
            Set<String> members = cohorts.get(week);
            if (members != null) members.add(t.id());
        }

        // (tenant, Monday of the activity week) pairs since the first retention week of the oldest cohort.
        Set<String> activeWeeks = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT DISTINCT a.tenant_id::text AS tenant_id, "
                + "a.activity_date - (EXTRACT(ISODOW FROM a.activity_date)::int - 1) AS week_start "
                + "FROM public.user_daily_activity a JOIN public.tenants t ON t.id = a.tenant_id "
                + "WHERE t.id <> '" + SYSTEM_TENANT_ID + "'::uuid AND a.activity_date >= ?",
                oldestCohort.plusWeeks(1))) {
            LocalDate week = asLocalDate(row.get("week_start"));
            Object tenantId = row.get("tenant_id");
            if (week != null && tenantId != null) activeWeeks.add(tenantId + "|" + week);
        }

        List<RetentionCohort> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Set<String>> cohort : cohorts.entrySet()) {
            LocalDate start = cohort.getKey();
            Set<String> members = cohort.getValue();
            List<Double> cells = new ArrayList<>(columns);
            for (int k = 1; k <= columns; k++) {
                LocalDate week = start.plusWeeks(k);
                // A week counts once complete: its Sunday must be before today (in-progress / future → null).
                if (!week.plusDays(6).isBefore(today) || members.isEmpty()) {
                    cells.add(null);
                    continue;
                }
                long active = members.stream().filter(id -> activeWeeks.contains(id + "|" + week)).count();
                cells.add((double) active / members.size());
            }
            rows.add(new RetentionCohort(start, members.size(), Collections.unmodifiableList(cells)));
        }
        return new RetentionReport(columns, rows);
    }

    private static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ── Top stores ────────────────────────────────────────────────────────────

    @Override
    public TopStoresReport getTopStores(String from, String to) {
        LocalDate[] range = resolveRange(from, to);
        LocalDate fromDay = range[0];
        LocalDate toDay = range[1];
        long days = ChronoUnit.DAYS.between(fromDay, toDay) + 1;
        LocalDate previousFrom = fromDay.minusDays(days);
        OffsetDateTime curStart = startOf(fromDay);
        // Never count sales dated after today (device clocks ahead), whatever the requested range.
        LocalDate endExclusive = toDay.isAfter(today()) ? today().plusDays(1) : toDay.plusDays(1);

        // 19.11b: per chunk of tenants, one statement returning its top stores; the chunks are merged here.
        List<TenantRef> tenants = queryable(current(loadTenants()));
        Map<String, TenantRef> byId = new HashMap<>();
        tenants.forEach(t -> byId.put(t.id(), t));
        OffsetDateTime previousStart = startOf(previousFrom);
        OffsetDateTime end = startOf(endExclusive);
        String saleTime = "COALESCE(sa.occurred_at, sa.created_at)";
        List<RankedStore> ranked = new ArrayList<>(crossTenant.query(
                crossTenant.withPresence(branches(tenants)).stream()
                        .filter(t -> t.has(TenantTable.SALES_CORE, TenantTable.STORES)).toList(),
                (tenant, params) -> {
                    String s = TenantSchema.validate(tenant.schemaName());
                    params.addAll(List.of(curStart, curStart, curStart, previousStart, end));
                    return "SELECT " + CrossTenantSql.tenantLiteral(tenant) + "::text AS tenant_id, st.id::text AS store_id, "
                            + "st.name AS store_name, "
                            + "COALESCE(SUM(sa.total_amount) FILTER (WHERE " + saleTime + " >= ?), 0) AS gmv, "
                            + "COUNT(*) FILTER (WHERE " + saleTime + " >= ?) AS transactions, "
                            + "COALESCE(SUM(sa.total_amount) FILTER (WHERE " + saleTime + " < ?), 0) AS previous_gmv "
                            + "FROM " + s + ".sales sa JOIN " + s + ".stores st ON st.id = sa.store_id AND st.type = 'STORE' "
                            + "WHERE sa.status = 'COMPLETED' AND " + saleTime + " >= ? AND " + saleTime + " < ? "
                            + "GROUP BY st.id, st.name";
                },
                union -> "SELECT * FROM (" + union + ") u WHERE transactions > 0 "
                        + "ORDER BY gmv DESC, lower(store_name) COLLATE \"C\" NULLS LAST, tenant_id, store_id "
                        + "LIMIT " + TOP_STORES_LIMIT,
                (rs, n) -> {
                    long gmv = rs.getLong("gmv");
                    long transactions = rs.getLong("transactions");
                    String tenantId = rs.getString("tenant_id");
                    TenantRef tenant = byId.get(tenantId);
                    return new RankedStore(new TopStore(rs.getString("store_name"),
                            tenant == null ? UNNAMED_TENANT : tenantName(tenant), gmv, transactions,
                            Math.round((double) gmv / transactions), growthPercent(gmv, rs.getLong("previous_gmv"))),
                            tenantId, rs.getString("store_id"));
                }));
        // The chunk tops are merged in the SQL order (same tie-break), so the global top 20 is deterministic.
        ranked.sort(RankedStore.ORDER);
        return new TopStoresReport(fromDay, toDay, ranked.stream().limit(TOP_STORES_LIMIT).map(RankedStore::store).toList());
    }

    /** A top store with the keys of its SQL order: gmv desc, lower(name) (C collation), tenant, store. */
    private record RankedStore(TopStore store, String tenantId, String storeId) {
        static final Comparator<RankedStore> ORDER = Comparator
                .comparingLong((RankedStore r) -> r.store().gmv()).reversed()
                .thenComparing(r -> r.store().storeName() == null ? null : r.store().storeName().toLowerCase(Locale.ROOT),
                        Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(RankedStore::tenantId, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(RankedStore::storeId, Comparator.nullsLast(Comparator.<String>naturalOrder()));
    }

    /** (current − previous) / previous in %, 1 decimal; null when previous = 0 (« Nouveau »). */
    static Double growthPercent(long current, long previous) {
        if (previous <= 0) return null;
        return BigDecimal.valueOf(current - previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** Inclusive WAT day range; both null → last 30 days (today included). */
    LocalDate[] resolveRange(String from, String to) {
        boolean noFrom = from == null || from.isBlank();
        boolean noTo = to == null || to.isBlank();
        if (noFrom && noTo) {
            LocalDate today = today();
            return new LocalDate[]{today.minusDays(DEFAULT_TOP_STORES_DAYS - 1L), today};
        }
        if (noFrom || noTo) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Indiquez les deux dates de la période");
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
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > MAX_RANGE_DAYS) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                    "La période ne peut pas dépasser " + MAX_RANGE_DAYS + " jours");
        }
        return new LocalDate[]{fromDay, toDay};
    }

    // ── Adoption ──────────────────────────────────────────────────────────────

    @Override
    public AdoptionReport getAdoption() {
        LocalDate today = today();
        LocalDate windowStart = today.minusDays(ACTIVE_WINDOW_DAYS - 1);
        OffsetDateTime start = startOf(windowStart);
        OffsetDateTime end = startOf(today.plusDays(1));

        Set<String> activeIds = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT DISTINCT tenant_id::text AS tenant_id FROM public.user_daily_activity "
                + "WHERE activity_date >= ? AND activity_date <= ?",
                windowStart, today)) {
            Object id = row.get("tenant_id");
            if (id != null) activeIds.add(id.toString());
        }

        // 19.11b: one row per active tenant inside chunked unions, counted in SQL; a feature whose table is
        // absent from the schema (module never used) is simply not used. A schema without any table is skipped.
        List<TenantBranch> active = crossTenant.withPresence(branches(queryable(current(loadTenants())).stream()
                        .filter(t -> activeIds.contains(t.id())).toList()))
                .stream().filter(t -> !t.tables().isEmpty()).toList();
        long[] using = new long[FEATURES.size()];
        long activeTenants = 0;
        for (long[] chunk : crossTenant.query(active, (tenant, params) -> adoptionBranch(tenant, params, start, end),
                union -> "SELECT COUNT(*), COUNT(*) FILTER (WHERE sales), COUNT(*) FILTER (WHERE inventory), "
                        + "COUNT(*) FILTER (WHERE transfers), COUNT(*) FILTER (WHERE whatsapp_reports), "
                        + "COUNT(*) FILTER (WHERE stock_alerts), COUNT(*) FILTER (WHERE offline_sync) "
                        + "FROM (" + union + ") u",
                (rs, n) -> {
                    long[] row = new long[FEATURES.size() + 1];
                    for (int i = 0; i < row.length; i++) row[i] = rs.getLong(i + 1);
                    return row;
                })) {
            activeTenants += chunk[0];
            for (int i = 0; i < using.length; i++) using[i] += chunk[i + 1];
        }

        List<FeatureAdoption> features = new ArrayList<>();
        for (int i = 0; i < FEATURES.size(); i++) {
            double rate = activeTenants == 0 ? 0 : (double) using[i] / activeTenants;
            features.add(new FeatureAdoption(FEATURES.get(i), using[i], rate));
        }
        return new AdoptionReport(activeTenants, features);
    }

    /** One active tenant's feature flags (FEATURES order); a feature whose table is absent reads false. */
    private static String adoptionBranch(TenantBranch tenant, List<Object> params, OffsetDateTime start,
                                         OffsetDateTime end) {
        String s = TenantSchema.validate(tenant.schemaName());
        String sales = "false", inventory = "false", transfers = "false", reports = "false", alerts = "false",
                sync = "false";
        if (tenant.has(TenantTable.SALES_CORE)) {
            sales = "EXISTS (SELECT 1 FROM " + s + ".sales WHERE status = 'COMPLETED' AND "
                    + SALE_TIME + " >= ? AND " + SALE_TIME + " < ?)";
            params.add(start);
            params.add(end);
        }
        if (tenant.has(TenantTable.INVENTORY_SESSIONS)) {
            inventory = "EXISTS (SELECT 1 FROM " + s + ".inventory_sessions WHERE started_at >= ? AND started_at < ?)";
            params.add(start);
            params.add(end);
        }
        if (tenant.has(TenantTable.STOCK_TRANSFERS)) {
            transfers = "EXISTS (SELECT 1 FROM " + s + ".stock_transfers WHERE occurred_at >= ? AND occurred_at < ?)";
            params.add(start);
            params.add(end);
        }
        if (tenant.has(TenantTable.REPORTS)) {
            reports = "EXISTS (SELECT 1 FROM " + s + ".reports WHERE delivery_status = 'SENT' AND "
                    + "COALESCE(last_attempt_at, created_at) >= ? AND COALESCE(last_attempt_at, created_at) < ?)";
            params.add(start);
            params.add(end);
        }
        if (tenant.has(TenantTable.TENANT_PREFERENCES, TenantTable.NOTIFICATION_COOLDOWNS)) {
            // Stock alerts: preference enabled AND a stock alert actually sent in the window
            // (notification_cooldowns keeps the last send per product/store).
            alerts = "(COALESCE((SELECT bool_or(stock_alert_enabled) FROM " + s + ".tenant_preferences), false) "
                    + "AND EXISTS (SELECT 1 FROM " + s + ".notification_cooldowns WHERE cooldown_type = 'STOCK_ALERT' "
                    + "AND last_sent_at >= ? AND last_sent_at < ?))";
            params.add(start);
            params.add(end);
        }
        if (tenant.has(TenantTable.SYNC_OPERATIONS_LOG)) {
            sync = "EXISTS (SELECT 1 FROM " + s + ".sync_operations_log WHERE processed_at >= ? AND processed_at < ?)";
            params.add(start);
            params.add(end);
        }
        return "SELECT " + sales + " AS sales, " + inventory + " AS inventory, " + transfers + " AS transfers, "
                + reports + " AS whatsapp_reports, " + alerts + " AS stock_alerts, " + sync + " AS offline_sync";
    }

    /** Optional integer query parameter in 1..max (null/blank → default), else 422 VALIDATION_ERROR. */
    static int boundedInt(String raw, String name, int defaultValue, int max) { // name: French label (« Le nombre de mois »)
        if (raw == null || raw.isBlank()) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, name + " doit être un nombre entier entre 1 et " + max);
        }
        if (value < 1 || value > max) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, name + " doit être compris entre 1 et " + max);
        }
        return value;
    }

    // ── Tenant loading & JDBC helpers ─────────────────────────────────────────

    private static List<TenantBranch> branches(List<TenantRef> tenants) {
        return tenants.stream()
                .map(t -> new TenantBranch(t.id(), t.name(), t.schemaName(), t.status(), t.createdAt()))
                .toList();
    }

    private record TenantRef(String id, String schemaName, String name, Instant createdAt, String status) {
        boolean deleted() { return "DELETED".equals(status); }
    }

    /** Every tenant ever created (DELETED included), KV-ADMIN excluded — history reads use it as is. */
    private List<TenantRef> loadTenants() {
        return jdbc.queryForList(
                        "SELECT id::text AS id, schema_name, name, created_at, status FROM public.tenants "
                        + "WHERE id <> '" + SYSTEM_TENANT_ID + "'::uuid ORDER BY name")
                .stream()
                .map(r -> new TenantRef((String) r.get("id"), (String) r.get("schema_name"),
                        (String) r.get("name"), asInstant(r.get("created_at")), (String) r.get("status")))
                .toList();
    }

    /** Current platform tenants (current-state KPIs): not DELETED. */
    private static List<TenantRef> current(List<TenantRef> tenants) {
        return tenants.stream().filter(t -> !t.deleted()).toList();
    }

    /** Tenants whose schema name may be interpolated in SQL. */
    private static List<TenantRef> queryable(List<TenantRef> tenants) {
        return tenants.stream()
                .filter(t -> {
                    if (TenantSchema.isValid(t.schemaName())) return true;
                    // Tenant id only — never the (invalid) schema name.
                    log.warn("Platform analytics: tenant {} skipped (invalid schema name)", t.id());
                    return false;
                })
                .toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(WAT));
    }

    /** WAT midnight of {@code day}, bound as timestamptz (no dependency on the session time zone). */
    private static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(WAT).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String tenantName(TenantRef tenant) {
        return tenant.name() == null || tenant.name().isBlank() ? UNNAMED_TENANT : tenant.name();
    }

    private static YearMonth parseMonth(Object value) {
        if (value == null) return null;
        try {
            return YearMonth.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
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
