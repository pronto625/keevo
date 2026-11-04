package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.subscription.plan.adapter.in.scheduling.UsageCounterPurgeJob;
import com.keevo.subscription.plan.adapter.out.persistence.impl.JdbcUsageCounterStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 19.8 — usage counters and quotas on PostgreSQL (a fresh database migrated by the public Flyway scripts, V44
 * included): the race (10 concurrent increments on a limit of 5 → exactly 5), DAY then MONTH without partial count,
 * the pack fallback (MONTH / LIFETIME yes, DAY never), unlimited metrics still counted, the period rollover in WAT,
 * the refusal details, the status read, the purge and V44 idempotence.
 */
@DisplayName("Usage quotas — atomic counters on PostgreSQL (Story 19.8)")
class UsageQuotaPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_usage_quotas";
    private static final String PLAN = "TEST_QUOTA";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    /** 2026-11-04 10:00 WAT. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T09:00:00Z"));
    private UsageQuotaService service;
    private JdbcUsageCounterStore store;
    private UUID tenant;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES (?, 'Test quotas', 99, "
                + "false, 'PAID') ON CONFLICT (code) DO NOTHING", PLAN);
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ?", PLAN);
        tenant = tenant(PLAN);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        store = new JdbcUsageCounterStore(jdbc);
        List<PlanDefinition> plans = new ArrayList<>(PlanCatalog.SEED.all());
        plans.add(new PlanDefinition(PlanType.of(PLAN), "Test quotas", null, 99, false, PlanKind.PAID, null));
        service = new UsageQuotaService(store, PlanCatalog.of(plans, PlanCatalog.SEED.settings()), clock);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static UUID tenant(String plan) {
        UUID id = UUID.randomUUID();
        String code = id.toString().substring(0, 8);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) VALUES (?, now(), now(), ?, 1, 1, 1, ?, ?, 'ACTIVE')",
                id, code, plan, "kv_" + code.substring(0, 6));
        return id;
    }

    private static void quota(String metric, String period, int limit) {
        jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, ?, ?, ?)",
                PLAN, metric, period, limit);
    }

    /** One increment in its own transaction (the caller's). */
    private void increment(UsageMetric metric, int n) {
        tx.executeWithoutResult(s -> service.assertAndIncrement(tenant, metric, n));
    }

    private DomainException refused(UsageMetric metric, int n) {
        return catchThrowableOfType(DomainException.class, () -> increment(metric, n));
    }

    private long used(UsageMetric metric, String key) {
        return store.used(tenant, metric, key);
    }

    private Long credits(UsageMetric metric) {
        List<Long> rows = jdbc.queryForList("SELECT remaining FROM public.usage_credits WHERE tenant_id = ? "
                + "AND metric = ?", Long.class, tenant, metric.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── race ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("race: 10 concurrent increments on a limit of 5 → exactly 5 succeed, 5 QUOTA_EXCEEDED, used = 5")
    void concurrentIncrementsNeverOvershoot() throws Exception {
        quota("DOCUMENTS", "MONTH", 5);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit((Callable<String>) () -> {
                    start.await();
                    try {
                        increment(UsageMetric.DOCUMENTS, 1);
                        return "OK";
                    } catch (DomainException e) {
                        return e.getDomainCode();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn("OK"::equals).hasSize(5);
            assertThat(outcomes).filteredOn("QUOTA_EXCEEDED"::equals).hasSize(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(used(UsageMetric.DOCUMENTS, "2026-11")).isEqualTo(5);
    }

    // ── periods ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DAY then MONTH: the daily cap refuses first; a MONTH refusal leaves no partial DAY count")
    void dayThenMonthWithoutPartialCount() {
        quota("AI_CREDITS", "DAY", 2);
        quota("AI_CREDITS", "MONTH", 3);
        increment(UsageMetric.AI_CREDITS, 1);
        increment(UsageMetric.AI_CREDITS, 1);
        DomainException day = refused(UsageMetric.AI_CREDITS, 1);
        assertThat(day.getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        assertThat(day.getDetails()).containsEntry("period", "DAY").containsEntry("limit", 2)
                .containsEntry("used", 2L).containsEntry("resetAt", "2026-11-04T23:00:00Z");

        now.set(Instant.parse("2026-11-05T09:00:00Z")); // next day: month 2 of 3
        increment(UsageMetric.AI_CREDITS, 1);
        DomainException month = refused(UsageMetric.AI_CREDITS, 1);
        assertThat(month.getDetails()).containsEntry("period", "MONTH").containsEntry("limit", 3)
                .containsEntry("used", 3L).containsEntry("resetAt", "2026-11-30T23:00:00Z");
        assertThat(used(UsageMetric.AI_CREDITS, "2026-11-05")).as("no partial DAY count").isEqualTo(1);
        assertThat(used(UsageMetric.AI_CREDITS, "2026-11")).isEqualTo(3);
    }

    @Test
    @DisplayName("an increment larger than the room left is refused whole (n > remaining), nothing counted")
    void incrementLargerThanRoom() {
        quota("SMS", "MONTH", 5);
        increment(UsageMetric.SMS, 4);
        assertThat(refused(UsageMetric.SMS, 2).getDetails()).containsEntry("used", 4L);
        assertThat(refused(UsageMetric.SMS, 6).getDetails()).containsEntry("used", 4L);
        increment(UsageMetric.SMS, 1);
        assertThat(used(UsageMetric.SMS, "2026-11")).isEqualTo(5);
    }

    // ── packs ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pack fallback: MONTH used up → the pack pays (counter not pushed past its limit), then refusal")
    void packAfterMonth() {
        quota("SMS", "MONTH", 1);
        tx.executeWithoutResult(s -> assertThat(service.add(tenant, UsageMetric.SMS, 2)).isEqualTo(2L));
        increment(UsageMetric.SMS, 1);
        increment(UsageMetric.SMS, 1);
        assertThat(credits(UsageMetric.SMS)).isEqualTo(1);
        increment(UsageMetric.SMS, 1);
        assertThat(credits(UsageMetric.SMS)).isZero();
        assertThat(refused(UsageMetric.SMS, 1).getDetails()).containsEntry("period", "MONTH");
        assertThat(used(UsageMetric.SMS, "2026-11")).isEqualTo(1);
        assertThat(service.status(tenant).credits()).containsEntry(UsageMetric.SMS, 0L);
    }

    @Test
    @DisplayName("LIFETIME (FREE's 5 SMS): refused once used unless a pack remains; resetAt null")
    void lifetime() {
        quota("SMS", "LIFETIME", 5);
        increment(UsageMetric.SMS, 5);
        DomainException e = refused(UsageMetric.SMS, 1);
        assertThat(e.getDetails()).containsEntry("period", "LIFETIME").containsEntry("limit", 5)
                .containsEntry("used", 5L).containsEntry("resetAt", null)
                .containsEntry("metric", "SMS").containsEntry("planCode", PLAN).containsEntry("planLabel", "Test quotas");
        tx.executeWithoutResult(s -> service.add(tenant, UsageMetric.SMS, 10));
        increment(UsageMetric.SMS, 1);
        assertThat(credits(UsageMetric.SMS)).isEqualTo(9);
        assertThat(used(UsageMetric.SMS, QuotaPeriod.LIFETIME_KEY)).isEqualTo(5);
    }

    @Test
    @DisplayName("daily cap: never paid from a pack — refused, pack untouched")
    void dayNeverPaidByPack() {
        quota("AI_CREDITS", "DAY", 1);
        tx.executeWithoutResult(s -> service.add(tenant, UsageMetric.AI_CREDITS, 50));
        increment(UsageMetric.AI_CREDITS, 1);
        assertThat(refused(UsageMetric.AI_CREDITS, 1).getDetails()).containsEntry("period", "DAY");
        assertThat(credits(UsageMetric.AI_CREDITS)).isEqualTo(50);
    }

    // ── unlimited, rollover, arguments ───────────────────────────────────────

    @Test
    @DisplayName("unlimited (no quota row): allowed and still counted on MONTH and DAY (no LIFETIME counter)")
    void unlimitedStillCounted() {
        for (int i = 0; i < 3; i++) {
            increment(UsageMetric.DOCUMENTS, 1);
        }
        assertThat(used(UsageMetric.DOCUMENTS, "2026-11")).isEqualTo(3);
        assertThat(used(UsageMetric.DOCUMENTS, "2026-11-04")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.usage_counters WHERE tenant_id = ? "
                + "AND period = 'LIFETIME'", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForList("SELECT period FROM public.usage_counters WHERE tenant_id = ? "
                + "AND period_key = '2026-11'", String.class, tenant)).containsExactly("MONTH");
    }

    @Test
    @DisplayName("a metric with only a DAY quota (BUSINESS AI): MONTH counted too, so a monthly quota added later "
            + "starts from the real figure")
    void monthCountedUnderDayQuotaOnly() {
        quota("AI_CREDITS", "DAY", 50);
        increment(UsageMetric.AI_CREDITS, 2);
        now.set(Instant.parse("2026-11-05T09:00:00Z"));
        increment(UsageMetric.AI_CREDITS, 3);
        assertThat(used(UsageMetric.AI_CREDITS, "2026-11")).isEqualTo(5);
        assertThat(used(UsageMetric.AI_CREDITS, "2026-11-05")).isEqualTo(3);

        quota("AI_CREDITS", "MONTH", 6); // added later: the month already holds 5
        increment(UsageMetric.AI_CREDITS, 1);
        assertThat(refused(UsageMetric.AI_CREDITS, 1).getDetails()).containsEntry("period", "MONTH")
                .containsEntry("used", 6L).containsEntry("metricLabel", "Crédits IA")
                .containsEntry("periodLabel", "par mois");
    }

    @Test
    @DisplayName("pack paid once per increment: MONTH and LIFETIME both used up → one more increment costs exactly 1")
    void packPaidOnceForMonthAndLifetime() {
        quota("SMS", "MONTH", 1);
        quota("SMS", "LIFETIME", 1);
        tx.executeWithoutResult(s -> service.add(tenant, UsageMetric.SMS, 2));
        increment(UsageMetric.SMS, 1); // both quotas reach their limit
        assertThat(credits(UsageMetric.SMS)).isEqualTo(2L);
        increment(UsageMetric.SMS, 1);
        assertThat(credits(UsageMetric.SMS)).isEqualTo(1L);
        assertThat(used(UsageMetric.SMS, "2026-11")).isEqualTo(1);
        assertThat(used(UsageMetric.SMS, QuotaPeriod.LIFETIME_KEY)).isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant whose plan code is unknown to the catalogue gets the free plan's quotas")
    void unknownPlanGetsFreeQuotas() {
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES ('TEST_UNKNOWN', "
                + "'Inconnu', 99, false, 'PAID') ON CONFLICT (code) DO NOTHING"); // in base, not in the catalogue
        tenant = tenant("TEST_UNKNOWN");
        jdbc.update("INSERT INTO public.usage_counters (tenant_id, metric, period, period_key, used) "
                + "VALUES (?, 'DOCUMENTS', 'MONTH', '2026-11', 300)", tenant);
        DomainException e = refused(UsageMetric.DOCUMENTS, 1);
        assertThat(e.getDetails()).containsEntry("limit", 300).containsEntry("planCode", "FREE")
                .containsEntry("planLabel", "Gratuit");
        assertThat(service.status(tenant).quotas()).extracting(q -> q.metric() + "/" + q.period() + "/" + q.limit())
                .contains("DOCUMENTS/MONTH/300", "SMS/LIFETIME/5");
    }

    @Test
    @DisplayName("rollover: at 23:59 WAT on 30/11 the month is 2026-11 (resetAt 1/12 00:00 WAT); one minute later a "
            + "fresh 2026-12 counter")
    void monthRollover() {
        quota("DOCUMENTS", "MONTH", 1);
        now.set(Instant.parse("2026-11-30T22:59:00Z"));
        increment(UsageMetric.DOCUMENTS, 1);
        assertThat(refused(UsageMetric.DOCUMENTS, 1).getDetails())
                .containsEntry("resetAt", "2026-11-30T23:00:00Z");
        UsageStatus.QuotaUsage before = service.status(tenant).quotas().stream()
                .filter(q -> q.metric() == UsageMetric.DOCUMENTS).findFirst().orElseThrow();
        assertThat(before.used()).isEqualTo(1);
        assertThat(before.remaining()).isZero();

        now.set(Instant.parse("2026-11-30T23:00:00Z")); // 1/12 00:00 WAT
        increment(UsageMetric.DOCUMENTS, 1);
        assertThat(used(UsageMetric.DOCUMENTS, "2026-12")).isEqualTo(1);
        assertThat(used(UsageMetric.DOCUMENTS, "2026-11")).isEqualTo(1);
        UsageStatus.QuotaUsage after = service.status(tenant).quotas().stream()
                .filter(q -> q.metric() == UsageMetric.DOCUMENTS).findFirst().orElseThrow();
        assertThat(after.resetAt()).isEqualTo(Instant.parse("2026-12-31T23:00:00Z"));
    }

    @Test
    @DisplayName("n < 1 refused; unknown tenant → TENANT_NOT_FOUND")
    void arguments() {
        assertThatThrownBy(() -> increment(UsageMetric.SMS, 0)).isInstanceOf(IllegalArgumentException.class);
        UUID unknown = UUID.randomUUID();
        assertThat(catchThrowableOfType(DomainException.class, () -> tx.executeWithoutResult(
                s -> service.assertAndIncrement(unknown, UsageMetric.SMS, 1))).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND);
    }

    @Test
    @DisplayName("by schema: same counting, the tenant resolved in the quota read")
    void bySchema() {
        quota("EMAILS", "MONTH", 1);
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenant);
        tx.executeWithoutResult(s -> service.assertAndIncrementForSchema(schema, UsageMetric.EMAILS, 1));
        assertThat(catchThrowableOfType(DomainException.class, () -> tx.executeWithoutResult(
                s -> service.assertAndIncrementForSchema(schema, UsageMetric.EMAILS, 1))).getErrorCode())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        assertThat(service.statusForSchema(schema).quotas()).isNotEmpty();
    }

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status: one entry per quota (DAY and MONTH for AI), one MONTH entry without limit per unlimited "
            + "metric, packs by metric")
    void status() {
        quota("AI_CREDITS", "MONTH", 500);
        quota("AI_CREDITS", "DAY", 20);
        quota("SMS", "LIFETIME", 5);
        increment(UsageMetric.AI_CREDITS, 3);
        increment(UsageMetric.DOCUMENTS, 1);
        tx.executeWithoutResult(s -> service.add(tenant, UsageMetric.SMS, 7));

        UsageStatus status = service.status(tenant);
        assertThat(status.quotas()).extracting(q -> q.metric() + "/" + q.period() + "/" + q.limit() + "/"
                        + q.used() + "/" + q.remaining())
                .containsExactly("DOCUMENTS/MONTH/null/1/null", "SMS/MONTH/null/0/null", "SMS/LIFETIME/5/0/5",
                        "EMAILS/MONTH/null/0/null", "AI_CREDITS/MONTH/500/3/497", "AI_CREDITS/DAY/20/3/17");
        assertThat(status.quotas()).filteredOn(q -> q.period() == QuotaPeriod.LIFETIME)
                .allSatisfy(q -> assertThat(q.resetAt()).isNull());
        assertThat(status.credits()).isEqualTo(Map.of(UsageMetric.SMS, 7L));
    }

    // ── purge, V44 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("purge: DAY counters older than 40 days and MONTH counters older than 25 months deleted; LIFETIME "
            + "and recent counters kept")
    void purge() {
        for (String[] row : List.of(new String[] {"DAY", "2026-09-24"}, new String[] {"DAY", "2026-09-25"},
                new String[] {"MONTH", "2024-09"}, new String[] {"MONTH", "2024-10"},
                new String[] {"LIFETIME", "LIFETIME"})) {
            jdbc.update("INSERT INTO public.usage_counters (tenant_id, metric, period, period_key, used) "
                    + "VALUES (?, 'SMS', ?, ?, 1)", tenant, row[0], row[1]);
        }
        new UsageCounterPurgeJob(store, Clock.fixed(now.get(), ZoneOffset.UTC)).purge(); // today 2026-11-04 → cutoffs 2026-09-25 and 2024-10
        assertThat(jdbc.queryForList("SELECT period_key FROM public.usage_counters WHERE tenant_id = ? "
                + "ORDER BY period_key", String.class, tenant))
                .containsExactly("2024-10", "2026-09-25", "LIFETIME");
    }

    @Test
    @DisplayName("V44: seed per the Bref grid; idempotent — run again, an edited quota is kept")
    void v44Idempotent() throws Exception {
        assertThat(jdbc.queryForList("SELECT metric || '/' || period || '=' || quota_limit FROM public.plan_quotas "
                + "WHERE plan_type = 'FREE' ORDER BY 1", String.class))
                .containsExactly("AI_CREDITS/DAY=0", "AI_CREDITS/MONTH=0", "DOCUMENTS/MONTH=300", "EMAILS/MONTH=100",
                        "SMS/LIFETIME=5");
        assertThat(jdbc.queryForList("SELECT metric || '/' || period || '=' || quota_limit FROM public.plan_quotas "
                + "WHERE plan_type = 'BUSINESS_TRIAL' ORDER BY 1", String.class))
                .containsExactly("AI_CREDITS/DAY=50", "SMS/MONTH=80");
        assertThat(jdbc.queryForList("SELECT metric || '/' || period || '=' || quota_limit FROM public.plan_quotas "
                + "WHERE plan_type = 'PREMIUM' ORDER BY 1", String.class))
                .containsExactly("AI_CREDITS/DAY=30", "AI_CREDITS/MONTH=750", "SMS/MONTH=50");
        jdbc.update("UPDATE public.plan_quotas SET quota_limit = 301 WHERE plan_type = 'FREE' AND metric = 'DOCUMENTS'");
        try {
            String sql;
            try (var in = getClass().getResourceAsStream("/db/migration/V44__usage_quotas.sql")) {
                sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
                st.execute(sql);
            }
            assertThat(jdbc.queryForObject("SELECT quota_limit FROM public.plan_quotas WHERE plan_type = 'FREE' "
                    + "AND metric = 'DOCUMENTS'", Integer.class)).isEqualTo(301);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.plan_quotas WHERE plan_type IN ('FREE', "
                    + "'PRO', 'TEAM', 'BUSINESS', 'BUSINESS_TRIAL', 'PREMIUM_TRIAL', 'PREMIUM')", Integer.class))
                    .isEqualTo(20);
        } finally {
            jdbc.update("UPDATE public.plan_quotas SET quota_limit = 300 WHERE plan_type = 'FREE' "
                    + "AND metric = 'DOCUMENTS'");
        }
    }
}
