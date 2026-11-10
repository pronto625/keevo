package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.capability.entitlement.UnknownPlanException;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.7a — the plan catalogue on PostgreSQL (a fresh database migrated by the public Flyway scripts): the V42
 * seed (three plans, today's labels and kinds, 6-month trial), the cache (a write through the port is seen at once, a
 * write made elsewhere after the TTL or on a miss), the fallback (last catalogue kept, else the seed), the foreign keys
 * that replaced the closed lists of codes, and the plan profiles without any limit in the code.
 */
@DisplayName("Plan catalogue — JDBC adapter, cache, foreign keys (Story 19.7a)")
class JdbcPlanCatalogPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_plan_catalogue";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static final PlanType TEAM = PlanType.of("TEAM");
    private static final PlanType BUSINESS_TRIAL = PlanType.of("BUSINESS_TRIAL");

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

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
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @AfterEach
    void restoreSeed() {
        jdbc.update("DELETE FROM public.profile_modules WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("UPDATE public.plan_settings SET free_plan_code = 'FREE', trial_plan_code = 'BUSINESS_TRIAL', "
                + "trial_months = 1, default_paid_plan_code = 'TEAM'");
        jdbc.update("DELETE FROM public.plans WHERE code LIKE 'TEST\\_%'");
        jdbc.update("UPDATE public.plans SET label = 'Essai Premium' WHERE code = 'PREMIUM_TRIAL'");
    }

    private JdbcPlanCatalog catalog(DataSource source) {
        return new JdbcPlanCatalog(source, nanos::get);
    }

    @Test
    @DisplayName("V42 + V43 seed: FREE / PRO / TEAM / BUSINESS / BUSINESS_TRIAL, then PREMIUM_TRIAL and PREMIUM archived "
            + "and hidden; settings FREE, BUSINESS_TRIAL, 1 month, default paid TEAM; V1 code from the kind")
    void seed() {
        JdbcPlanCatalog catalog = catalog(dataSource);

        assertThat(catalog.all()).extracting(p -> p.code().code() + ":" + p.label() + ":" + p.kind() + ":"
                        + p.sortOrder() + ":" + p.visible() + ":" + p.archived())
                .containsExactly("FREE:Gratuit:FREE:0:true:false", "PRO:Pro:PAID:10:true:false",
                        "TEAM:Team:PAID:20:true:false", "BUSINESS:Business:PAID:30:true:false",
                        "BUSINESS_TRIAL:Essai Business:TRIAL:40:true:false",
                        "PREMIUM_TRIAL:Essai Premium:TRIAL:90:false:true", "PREMIUM:Premium:PAID:91:false:true");
        assertThat(catalog.settings()).isEqualTo(new PlanSettings(PlanType.FREE, PlanType.of("BUSINESS_TRIAL"), 1,
                PlanType.of("TEAM")));
        assertThat(catalog.defaultPaidPlan()).isEqualTo(PlanType.of("TEAM"));
        assertThat(catalog.v1Code(PlanType.of("BUSINESS_TRIAL"))).isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(catalog.v1Code(PlanType.of("PRO"))).isEqualTo(PlanType.PREMIUM);
        assertThat(catalog.v1Code(PlanType.PREMIUM_TRIAL)).isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(catalog.resolve("PREMIUM")).isSameAs(PlanType.PREMIUM);
        assertThatThrownBy(() -> catalog.resolve("NOPE")).isInstanceOf(UnknownPlanException.class)
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.label("NOPE", "payant")).isEqualTo("payant");
        // the built-in fallback (V42 seed) only names codes the catalogue still holds (archived, never deleted)
        assertThat(catalog.all()).extracting(PlanDefinition::code)
                .containsAll(PlanCatalog.SEED.all().stream().map(PlanDefinition::code).toList());
    }

    @Test
    @DisplayName("cache: a write through the port is seen at once; a write made elsewhere after the TTL, or on a miss")
    void cacheInvalidation() {
        JdbcPlanCatalog catalog = catalog(dataSource);
        assertThat(catalog.require("PREMIUM_TRIAL").label()).isEqualTo("Essai Premium");

        // through the port: label and kind changed, next read sees it
        catalog.save(new PlanDefinition(PlanType.of("TEST_PAID"), "Test payant", "décrit", 9, false, PlanKind.TRIAL,
                null));
        assertThat(catalog.kind(PlanType.of("TEST_PAID"))).isEqualTo(PlanKind.TRIAL);
        catalog.save(new PlanDefinition(PlanType.of("TEST_PAID"), "Test payant 2", "décrit", 9, false, PlanKind.PAID,
                null));
        assertThat(catalog.require("TEST_PAID").label()).isEqualTo("Test payant 2");
        assertThat(catalog.kind(PlanType.of("TEST_PAID"))).isEqualTo(PlanKind.PAID);
        catalog.saveSettings(new PlanSettings(PlanType.FREE, PlanType.of("BUSINESS_TRIAL"), 2, PlanType.of("PRO")));
        assertThat(catalog.trialMonths()).isEqualTo(2);
        assertThat(catalog.defaultPaidPlan()).isEqualTo(PlanType.of("PRO"));

        // elsewhere (another instance): cached until the TTL, then reloaded
        jdbc.update("UPDATE public.plans SET label = 'Essai modifié' WHERE code = 'PREMIUM_TRIAL'");
        assertThat(catalog.require("PREMIUM_TRIAL").label()).isEqualTo("Essai Premium");
        nanos.addAndGet(JdbcPlanCatalog.TTL.toNanos());
        assertThat(catalog.require("PREMIUM_TRIAL").label()).isEqualTo("Essai modifié");

        // a code created elsewhere: a miss reloads once the snapshot is older than a second
        jdbc.update("INSERT INTO public.plans (code, label, kind) VALUES ('TEST_NEW', 'Nouveau', 'FREE')");
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(catalog.find("TEST_NEW")).map(PlanDefinition::kind).contains(PlanKind.FREE);
        // invalidate(): reloaded on the next read
        jdbc.update("UPDATE public.plans SET label = 'Nouveau 2' WHERE code = 'TEST_NEW'");
        catalog.invalidate();
        assertThat(catalog.require("TEST_NEW").label()).isEqualTo("Nouveau 2");
    }

    @Test
    @DisplayName("fallback: a failing reload keeps the last catalogue read; never read → the built-in seed, no exception")
    void fallback() {
        AtomicBoolean down = new AtomicBoolean(false);
        DataSource flaky = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                if (down.get()) {
                    throw new SQLException("database down");
                }
                return super.getConnection();
            }
        };
        JdbcPlanCatalog catalog = catalog(flaky);
        catalog.save(new PlanDefinition(PlanType.of("TEST_PAID"), "Test payant", null, 9, true, PlanKind.PAID, null));
        assertThat(catalog.find("TEST_PAID")).isPresent();

        down.set(true);
        nanos.addAndGet(JdbcPlanCatalog.TTL.toNanos());
        assertThat(catalog.find("TEST_PAID")).as("last catalogue kept").isPresent();

        // a write marks the catalogue stale; a reload failing right after keeps the data plan (never the seed)
        down.set(false);
        catalog.save(new PlanDefinition(PlanType.of("TEST_DATA"), "Donnée", null, 10, true, PlanKind.PAID, null));
        down.set(true);
        assertThat(catalog.find("TEST_DATA")).as("stale catalogue kept after a failing reload").isEmpty();
        assertThat(catalog.find("TEST_PAID")).as("the data plans of the last catalogue read").isPresent();
        assertThat(catalog.all()).isNotEqualTo(PlanCatalog.SEED.all());

        JdbcPlanCatalog cold = catalog(flaky);
        assertThat(cold.all()).isEqualTo(PlanCatalog.SEED.all());
        assertThat(cold.trialMonths()).isEqualTo(1); // 19.7b: the seed mirrors V43
        down.set(false);
        nanos.addAndGet(JdbcPlanCatalog.FALLBACK_RETRY.toNanos());
        assertThat(cold.find("TEST_PAID")).as("retried after the fallback delay").isPresent();
    }

    @Test
    @DisplayName("settings: the free plan must be a live plan of kind FREE, the trial plan a live one of kind TRIAL, "
            + "the default paid plan a live one of kind PAID (19.7b)")
    void settingsKinds() {
        JdbcPlanCatalog catalog = catalog(dataSource);
        catalog.save(new PlanDefinition(PlanType.of("TEST_OLD_FREE"), "Ancien", null, 9, false, PlanKind.FREE,
                java.time.Instant.now()));
        for (PlanSettings bad : List.of(
                new PlanSettings(PlanType.of("TEAM"), BUSINESS_TRIAL, 6, TEAM),          // paid « free » target
                new PlanSettings(PlanType.FREE, TEAM, 6, TEAM),                            // paid trial
                new PlanSettings(PlanType.of("TEST_OLD_FREE"), BUSINESS_TRIAL, 6, TEAM),  // archived
                new PlanSettings(PlanType.of("TEST_NONE"), BUSINESS_TRIAL, 6, TEAM),      // unknown
                new PlanSettings(PlanType.FREE, PlanType.PREMIUM_TRIAL, 6, TEAM),         // archived trial (V43)
                new PlanSettings(PlanType.FREE, BUSINESS_TRIAL, 1, PlanType.FREE),        // free « paid » default
                new PlanSettings(PlanType.FREE, BUSINESS_TRIAL, 1, BUSINESS_TRIAL),       // trial « paid » default
                new PlanSettings(PlanType.FREE, BUSINESS_TRIAL, 1, PlanType.PREMIUM),     // archived paid (V43)
                new PlanSettings(PlanType.FREE, BUSINESS_TRIAL, 1, PlanType.of("NOPE")))) { // unknown
            assertThatThrownBy(() -> catalog.saveSettings(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT free_plan_code || '/' || trial_plan_code FROM public.plan_settings",
                String.class)).isEqualTo("FREE/BUSINESS_TRIAL");
    }

    @Test
    @DisplayName("foreign keys: an unknown code is refused in tenants, plan events, plan profiles and settings")
    void foreignKeys() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) VALUES (?, now(), now(), 'KV-PLCAT1', 1, 1, 1, "
                + "'FREE', 'kv_plcat1', 'ACTIVE', 'Test')", tenant);
        try {
            assertThatThrownBy(() -> jdbc.update("UPDATE public.tenants SET plan_type = 'NOPE' WHERE id = ?", tenant))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, "
                    + "to_plan, cause) VALUES (?, 'FREE', 'NOPE', 'EXPIRY')", tenant))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, "
                    + "to_plan, cause) VALUES (?, 'NOPE', 'FREE', 'EXPIRY')", tenant))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.plan_profiles (plan_type) VALUES ('NOPE')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE public.plan_settings SET free_plan_code = 'NOPE'"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.plans (code, label, kind) "
                    + "VALUES ('TEST_X', 'X', 'GOLD')")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.plans (code, label, kind) "
                    + "VALUES ('test_lower', 'X', 'FREE')")).isInstanceOf(DataIntegrityViolationException.class);
            for (String reserved : List.of("ALL", "PAID")) { // words of the admin tenant filter
                assertThatThrownBy(() -> jdbc.update("INSERT INTO public.plans (code, label, kind) VALUES (?, 'X', "
                        + "'PAID')", reserved)).isInstanceOf(DataIntegrityViolationException.class);
            }
            // a catalogue code (no closed list any more) is accepted everywhere
            jdbc.update("INSERT INTO public.plans (code, label, kind) VALUES ('TEST_PAID', 'Test', 'PAID')");
            jdbc.update("UPDATE public.tenants SET plan_type = 'TEST_PAID' WHERE id = ?", tenant);
            jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause) "
                    + "VALUES (?, 'FREE', 'TEST_PAID', 'ADMIN_ACTIVATION')", tenant);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname LIKE 'fk\\_%plan' "
                    + "AND NOT convalidated", Integer.class)).isZero();
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenant); // events cascade
        }
    }

    @Test
    @DisplayName("profiles: no limit in the code — a plan without profile gets the free plan's (fallback, fail closed); "
            + "the kind of a profile comes from the catalogue")
    void profilesWithoutLimitInCode() {
        JdbcPlanCatalog catalog = catalog(dataSource);
        JdbcPlanProfileStore profiles = new JdbcPlanProfileStore(dataSource, catalog);
        catalog.save(new PlanDefinition(PlanType.of("TEST_PAID"), "Test payant", null, 9, true, PlanKind.PAID, null));

        PlanProfile free = profiles.profile(PlanType.FREE);
        assertThat(java.util.Arrays.asList(free.maxStores(), free.maxProducts(), free.maxEmployees(),
                free.maxEmployeesPerStore())).containsExactly(1, 500, 1, 1); // V43: FREE 1 employee, 1 per store
        assertThat(free.fallback()).isFalse();
        assertThat(profiles.profile(PlanType.PREMIUM).kind()).isEqualTo(PlanKind.PAID);

        PlanProfile none = profiles.profile(PlanType.of("TEST_PAID"));
        assertThat(none.planType()).isEqualTo(PlanType.of("TEST_PAID"));
        assertThat(List.of(none.maxStores(), none.maxProducts(), none.maxEmployees())).containsExactly(1, 500, 1);
        assertThat(none.fallback()).isTrue();
        assertThat(none.grants("exports")).isFalse();
        assertThat(none.grants("invoicing")).as("19.13 (V50): FREE grants invoicing").isTrue();

        jdbc.update("INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, max_employees) "
                + "VALUES ('TEST_PAID', 3, NULL, 5)");
        jdbc.update("INSERT INTO public.profile_modules (plan_type, module_id, granted) VALUES ('TEST_PAID', "
                + "'exports', true), ('TEST_PAID', 'invoicing', false)");
        PlanProfile own = profiles.profile(PlanType.of("TEST_PAID"));
        assertThat(own.fallback()).isFalse();
        assertThat(own.maxStores()).isEqualTo(3);
        assertThat(own.maxProducts()).isNull();
        assertThat(own.modules()).isEqualTo(Set.of("exports"));
        assertThat(own.grants("invoicing")).isFalse();
        assertThat(own.grants("alerts")).as("undecided paid module: the PAID kind grants it").isTrue();
    }

    @Test
    @DisplayName("profiles: a read fault keeps the last limits read (no limit in the code); batch read in one go")
    void profilesFaultKeepsLastRead() {
        AtomicBoolean down = new AtomicBoolean(false);
        DataSource flaky = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                if (down.get()) {
                    throw new SQLException("database down");
                }
                return super.getConnection();
            }
        };
        JdbcPlanCatalog catalog = catalog(dataSource); // the catalogue itself stays readable
        JdbcPlanProfileStore profiles = new JdbcPlanProfileStore(flaky, catalog);
        assertThat(profiles.profile(PlanType.FREE).fallback()).isFalse();
        assertThat(profiles.profiles(List.of(PlanType.FREE, PlanType.PREMIUM)).keySet())
                .containsExactly(PlanType.FREE, PlanType.PREMIUM);

        down.set(true);
        PlanProfile free = profiles.profile(PlanType.FREE);
        assertThat(List.of(free.maxStores(), free.maxProducts(), free.maxEmployees())).containsExactly(1, 500, 1);
        assertThat(free.fallback()).isTrue();
        PlanProfile batch = profiles.profiles(List.of(PlanType.FREE)).get(PlanType.FREE);
        assertThat(batch.maxEmployees()).isEqualTo(1);
        assertThat(batch.fallback()).isTrue();
    }

    // ── Story 19.7b — V43 (merged offer) ─────────────────────────────────────

    /** The V43 script, run again by hand (Flyway ran it once in {@link #migratePublic()}). */
    private static void rerunV43() throws Exception {
        String sql;
        try (var in = JdbcPlanCatalogPostgresIntegrationTest.class
                .getResourceAsStream("/db/migration/V43__plans_fusion.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static String profileRow(String plan) {
        return jdbc.queryForObject("SELECT concat_ws('/', coalesce(max_stores::text, '-'), "
                + "coalesce(max_employees::text, '-'), coalesce(max_employees_per_store::text, '-'), "
                + "coalesce(max_products::text, '-')) FROM public.plan_profiles WHERE plan_type = ?", String.class, plan);
    }

    private static List<String> granted(String plan) {
        return jdbc.queryForList("SELECT module_id FROM public.profile_modules WHERE plan_type = ? AND granted "
                + "ORDER BY module_id", String.class, plan);
    }

    @Test
    @DisplayName("V43: profiles (stores/employees/per store/products), modules of PRO and of the complete plans, "
            + "PLAN_MIGRATION cause accepted")
    void v43Seed() {
        assertThat(profileRow("FREE")).isEqualTo("1/1/1/500");
        assertThat(profileRow("PRO")).isEqualTo("3/-/3/-");
        assertThat(profileRow("TEAM")).isEqualTo("8/-/8/-");
        assertThat(profileRow("BUSINESS")).isEqualTo("-/-/-/-");
        assertThat(profileRow("BUSINESS_TRIAL")).isEqualTo("-/-/-/-");
        // 20.10 (V56): expenses granted from PRO up
        // 20.11 (V58): + finance; 21.4 (V65): + ai-assistant (every plan)
        assertThat(granted("PRO")).containsExactly("ai-assistant", "expenses", "exports", "finance", "invoicing",
                "report-config");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.profile_modules WHERE plan_type = 'PRO' "
                + "AND NOT granted", Integer.class)).as("the other paid modules explicitly withheld").isEqualTo(5);
        List<String> all = List.of("advanced-reports", "ai-assistant", "alerts", "counting", "expenses", "exports",
                "finance", "invoicing", "mcp-access", "multi-store", "profitability", "report-config"); // 21.9 (V66)
        for (String plan : List.of("TEAM", "BUSINESS", "BUSINESS_TRIAL")) {
            assertThat(granted(plan)).as(plan).isEqualTo(all);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE public.plan_profiles SET max_employees_per_store = 0 "
                + "WHERE plan_type = 'PRO'")).isInstanceOf(DataIntegrityViolationException.class);
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) VALUES (?, now(), now(), 'KV-PLMIG1', 1, 1, 1, "
                + "'TEAM', 'kv_plmig1', 'ACTIVE', 'Test')", tenant);
        try {
            jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause) "
                    + "VALUES (?, 'PREMIUM', 'TEAM', 'PLAN_MIGRATION')", tenant);
        } finally {
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenant);
        }
    }

    @Test
    @DisplayName("V43 is idempotent (run twice) and never overwrites an edited value (FREE profile, settings, label)")
    void v43Idempotent() throws Exception {
        String catalogue = "SELECT string_agg(code || ':' || label || ':' || kind || ':' || sort_order || ':' "
                + "|| visible || ':' || (archived_at IS NOT NULL), ',' ORDER BY code) FROM public.plans";
        String before = jdbc.queryForObject(catalogue, String.class);
        int modules = jdbc.queryForObject("SELECT count(*) FROM public.profile_modules", Integer.class);
        rerunV43();
        assertThat(jdbc.queryForObject(catalogue, String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.profile_modules", Integer.class))
                .isEqualTo(modules);
        assertThat(profileRow("FREE")).isEqualTo("1/1/1/500");

        // edited by a super-admin: kept by a rerun
        jdbc.update("UPDATE public.plan_profiles SET max_employees = 4, max_employees_per_store = NULL "
                + "WHERE plan_type = 'FREE'");
        jdbc.update("UPDATE public.plan_profiles SET max_stores = 5 WHERE plan_type = 'PRO'");
        jdbc.update("UPDATE public.plan_settings SET trial_months = 2, default_paid_plan_code = 'PRO'");
        jdbc.update("UPDATE public.plans SET label = 'Gratuit (édité)' WHERE code = 'FREE'");
        try {
            rerunV43();
            assertThat(profileRow("FREE")).isEqualTo("1/4/-/500");
            assertThat(profileRow("PRO")).isEqualTo("5/-/3/-");
            assertThat(jdbc.queryForObject("SELECT trial_plan_code || '/' || trial_months || '/' "
                    + "|| default_paid_plan_code FROM public.plan_settings", String.class))
                    .isEqualTo("BUSINESS_TRIAL/2/PRO");
            assertThat(jdbc.queryForObject("SELECT label FROM public.plans WHERE code = 'FREE'", String.class))
                    .isEqualTo("Gratuit (édité)");

            // a trial still on the archived PREMIUM_TRIAL moves to BUSINESS_TRIAL whatever its edited length
            jdbc.update("UPDATE public.plan_settings SET trial_plan_code = 'PREMIUM_TRIAL', trial_months = 3");
            rerunV43();
            assertThat(jdbc.queryForObject("SELECT trial_plan_code || '/' || trial_months FROM public.plan_settings",
                    String.class)).isEqualTo("BUSINESS_TRIAL/3");
            jdbc.update("UPDATE public.plan_settings SET trial_plan_code = 'PREMIUM_TRIAL', trial_months = 6");
            rerunV43();
            assertThat(jdbc.queryForObject("SELECT trial_plan_code || '/' || trial_months FROM public.plan_settings",
                    String.class)).isEqualTo("BUSINESS_TRIAL/1");
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint "
                    + "WHERE conname = 'tenant_plan_events_cause_check'", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.data_migrations", Integer.class)).isZero();
        } finally {
            jdbc.update("UPDATE public.plan_profiles SET max_employees = 1, max_employees_per_store = 1 "
                    + "WHERE plan_type = 'FREE'");
            jdbc.update("UPDATE public.plan_profiles SET max_stores = 3 WHERE plan_type = 'PRO'");
            jdbc.update("UPDATE public.plans SET label = 'Gratuit' WHERE code = 'FREE'");
        }
    }
}
