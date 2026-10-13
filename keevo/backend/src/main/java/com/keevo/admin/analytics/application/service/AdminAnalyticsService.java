package com.keevo.admin.analytics.application.service;

import com.keevo.admin.analytics.domain.model.*;
import com.keevo.admin.analytics.domain.port.in.*;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Supplier;

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
 *   <li>GMV = {@code SUM(total_amount)} of {@code COMPLETED} sales, sale time
 *       {@code COALESCE(occurred_at, created_at)} — same rule as 9-7. Day / week / month boundaries are WAT.</li>
 *   <li>Geography = the owner phone's calling code (V1 proxy: stores have no structured city).</li>
 * </ul>
 *
 * <p>Cross-schema reads use raw JdbcTemplate with schema-qualified SQL; schema names come from
 * {@code public.tenants.schema_name} and are gated by {@link TenantSchema#isValid} before interpolation.
 * Not {@code @Transactional}: a tenant whose query fails on its own schema / SQL / data error is skipped
 * without aborting the others; connection / resource failures are rethrown (9-7 rule).
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

    public AdminAnalyticsService(JdbcTemplate jdbc, Clock clock, OwnerRecipientResolver ownerRecipientResolver) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownerRecipientResolver = ownerRecipientResolver;
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
        long stores = 0, activeStores = 0, products = 0, sales = 0, gmv = 0;
        for (TenantRef tenant : queryable(tenants)) {
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // queryable() filters; re-checked at use
            Map<String, Object> row = safeSingleRow(tenant,
                    "SELECT (SELECT COUNT(*) FROM " + s + ".stores WHERE is_active = true AND type = 'STORE') AS store_count, "
                    + "(SELECT COUNT(*) FROM " + s + ".stores st WHERE st.is_active = true AND st.type = 'STORE' "
                    + "AND EXISTS (SELECT 1 FROM " + s + ".sales sa WHERE sa.store_id = st.id AND sa.status = 'COMPLETED' "
                    + "AND COALESCE(sa.occurred_at, sa.created_at) >= ? "
                    + "AND COALESCE(sa.occurred_at, sa.created_at) < ?)) AS active_store_count, "
                    + "(SELECT COUNT(*) FROM " + s + ".products WHERE archived = false) AS product_count, "
                    + "(SELECT COUNT(*) FROM " + s + ".sales WHERE status = 'COMPLETED') AS sales_count, "
                    + "(SELECT COALESCE(SUM(total_amount), 0) FROM " + s + ".sales WHERE status = 'COMPLETED') AS gmv",
                    activeSince, tomorrow);
            if (row == null) continue;
            stores += asLong(row.get("store_count"));
            activeStores += asLong(row.get("active_store_count"));
            products += asLong(row.get("product_count"));
            sales += asLong(row.get("sales_count"));
            gmv += asLong(row.get("gmv"));
        }
        return new AnalyticsOverview(dau, mau, ratio, tenants.size(), stores, activeStores, products, sales, gmv);
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
        int count = boundedInt(months, "months", DEFAULT_GROWTH_MONTHS, MAX_GROWTH_MONTHS);
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
        for (TenantRef tenant : queryable(current(tenants))) {
            if (!TenantSchema.isValid(tenant.schemaName())) { skipInvalid(tenant); continue; }
            List<Map<String, Object>> rows = safeRows(tenant,
                    "SELECT to_char(" + SALE_TIME + " AT TIME ZONE '" + WAT.getId() + "', 'YYYY-MM') AS month, "
                    + "COUNT(*) AS sales_count, COALESCE(SUM(total_amount), 0) AS gmv "
                    + "FROM " + tenant.schemaName() + ".sales WHERE status = 'COMPLETED' AND "
                    + SALE_TIME + " >= ? AND " + SALE_TIME + " < ? GROUP BY 1",
                    startOf(firstDay), startOf(endDay));
            for (Map<String, Object> row : rows) {
                long[] acc = sales.get(parseMonth(row.get("month")));
                if (acc == null) continue;
                acc[0] += asLong(row.get("sales_count"));
                acc[1] += asLong(row.get("gmv"));
            }
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
        Map<String, Long> byCountry = new HashMap<>();
        for (TenantRef tenant : queryable(current(loadTenants()))) {
            Optional<String> phone = safeCall(tenant,
                    () -> ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenant.schemaName()));
            if (phone == null) continue; // tenant skipped (its own data error)
            byCountry.merge(countryOf(phone.orElse(null)), 1L, Long::sum);
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
        int columns = boundedInt(weeks, "weeks", DEFAULT_RETENTION_WEEKS, MAX_RETENTION_WEEKS);
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

        List<TopStore> stores = new ArrayList<>();
        for (TenantRef tenant : queryable(current(loadTenants()))) {
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // queryable() filters; re-checked at use
            String saleTime = "COALESCE(sa.occurred_at, sa.created_at)";
            List<Map<String, Object>> rows = safeRows(tenant,
                    "SELECT st.name AS store_name, "
                    + "COALESCE(SUM(sa.total_amount) FILTER (WHERE " + saleTime + " >= ?), 0) AS gmv, "
                    + "COUNT(*) FILTER (WHERE " + saleTime + " >= ?) AS transactions, "
                    + "COALESCE(SUM(sa.total_amount) FILTER (WHERE " + saleTime + " < ?), 0) AS previous_gmv "
                    + "FROM " + s + ".sales sa JOIN " + s + ".stores st ON st.id = sa.store_id AND st.type = 'STORE' "
                    + "WHERE sa.status = 'COMPLETED' AND " + saleTime + " >= ? AND " + saleTime + " < ? "
                    + "GROUP BY st.id, st.name",
                    curStart, curStart, curStart, startOf(previousFrom), startOf(endExclusive));
            for (Map<String, Object> row : rows) {
                long transactions = asLong(row.get("transactions"));
                if (transactions == 0) continue;
                long gmv = asLong(row.get("gmv"));
                long previous = asLong(row.get("previous_gmv"));
                stores.add(new TopStore(
                        (String) row.get("store_name"), tenantName(tenant), gmv, transactions,
                        Math.round((double) gmv / transactions), growthPercent(gmv, previous)));
            }
        }
        stores.sort(Comparator.comparingLong(TopStore::gmv).reversed()
                .thenComparing(TopStore::storeName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new TopStoresReport(fromDay, toDay,
                List.copyOf(stores.subList(0, Math.min(TOP_STORES_LIMIT, stores.size()))));
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
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from and to must be provided together");
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
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > MAX_RANGE_DAYS) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "the range cannot exceed " + MAX_RANGE_DAYS + " days");
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

        long[] using = new long[FEATURES.size()];
        long activeTenants = 0;
        for (TenantRef tenant : queryable(current(loadTenants()))) {
            if (!activeIds.contains(tenant.id())) continue;
            String s = tenant.schemaName();
            if (!TenantSchema.isValid(s)) { skipInvalid(tenant); continue; } // queryable() filters; re-checked at use
            Map<String, Object> row = safeSingleRow(tenant,
                    "SELECT EXISTS (SELECT 1 FROM " + s + ".sales WHERE status = 'COMPLETED' AND "
                    + SALE_TIME + " >= ? AND " + SALE_TIME + " < ?) AS sales, "
                    + "EXISTS (SELECT 1 FROM " + s + ".inventory_sessions WHERE started_at >= ? AND started_at < ?) AS inventory, "
                    + "EXISTS (SELECT 1 FROM " + s + ".stock_transfers WHERE occurred_at >= ? AND occurred_at < ?) AS transfers, "
                    + "EXISTS (SELECT 1 FROM " + s + ".reports WHERE delivery_status = 'SENT' AND "
                    + "COALESCE(last_attempt_at, created_at) >= ? AND COALESCE(last_attempt_at, created_at) < ?) AS whatsapp_reports, "
                    // Stock alerts: preference enabled AND a stock alert actually sent in the window
                    // (notification_cooldowns keeps the last send per product/store).
                    + "(COALESCE((SELECT bool_or(stock_alert_enabled) FROM " + s + ".tenant_preferences), false) "
                    + "AND EXISTS (SELECT 1 FROM " + s + ".notification_cooldowns WHERE cooldown_type = 'STOCK_ALERT' "
                    + "AND last_sent_at >= ? AND last_sent_at < ?)) AS stock_alerts, "
                    + "EXISTS (SELECT 1 FROM " + s + ".sync_operations_log WHERE processed_at >= ? AND processed_at < ?) AS offline_sync",
                    start, end, start, end, start, end, start, end, start, end, start, end);
            if (row == null) continue;
            activeTenants++;
            String[] columns = {"sales", "inventory", "transfers", "whatsapp_reports", "stock_alerts", "offline_sync"};
            for (int i = 0; i < columns.length; i++) {
                if (Boolean.TRUE.equals(row.get(columns[i]))) using[i]++;
            }
        }

        List<FeatureAdoption> features = new ArrayList<>();
        for (int i = 0; i < FEATURES.size(); i++) {
            double rate = activeTenants == 0 ? 0 : (double) using[i] / activeTenants;
            features.add(new FeatureAdoption(FEATURES.get(i), using[i], rate));
        }
        return new AdoptionReport(activeTenants, features);
    }

    /** Optional integer query parameter in 1..max (null/blank → default), else 422 VALIDATION_ERROR. */
    static int boundedInt(String raw, String name, int defaultValue, int max) {
        if (raw == null || raw.isBlank()) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, name + " must be an integer between 1 and " + max);
        }
        if (value < 1 || value > max) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, name + " must be between 1 and " + max);
        }
        return value;
    }

    // ── Tenant loading & JDBC helpers ─────────────────────────────────────────

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

    private static void skipInvalid(TenantRef tenant) {
        log.warn("Platform analytics: tenant {} skipped (invalid schema name)", tenant.id());
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

    private Map<String, Object> safeSingleRow(TenantRef tenant, String sql, Object... args) {
        List<Map<String, Object>> rows = safeCall(tenant, () -> jdbc.queryForList(sql, args));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> safeRows(TenantRef tenant, String sql, Object... args) {
        List<Map<String, Object>> rows = safeCall(tenant, () -> jdbc.queryForList(sql, args));
        return rows == null ? List.of() : rows;
    }

    /** Result of a per-tenant call, or null when it failed on the tenant's own schema / SQL / data error;
     *  connection / resource failures are rethrown (never an all-zero 200). */
    private static <T> T safeCall(TenantRef tenant, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Platform analytics: tenant {} skipped ({})", tenant.id(), e.getClass().getSimpleName());
            return null;
        }
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
