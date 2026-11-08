package com.keevo.subscription.offer.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.subscription.offer.domain.model.Discount;
import com.keevo.subscription.offer.domain.model.DiscountScope;
import com.keevo.subscription.offer.domain.model.OfferChange;
import com.keevo.subscription.offer.domain.model.OfferEntity;
import com.keevo.subscription.offer.domain.model.PriceChange;
import com.keevo.subscription.offer.domain.model.TenantPlanRef;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 21.10 — V60 on PostgreSQL (a fresh database migrated by the public Flyway scripts) and the JDBC adapter of the
 * offer catalogue: seeded grid and cycles with their history, idempotent rerun, legacy payment backfill, price write
 * with history, the one-query live discount candidates, the promo-code uniqueness, plan reorder, profile copy, company
 * reads and the change journal.
 */
@DisplayName("Offer catalogue — V60 and JdbcOfferStore on PostgreSQL (Story 21.10)")
class OfferCataloguePostgresIntegrationTest {

    private static final String TEST_DB = "keevo_offer_catalogue";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcOfferStore store;
    private static TransactionTemplate tx;

    private final List<UUID> tenants = new ArrayList<>();

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
        store = new JdbcOfferStore(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM public.discounts");
        jdbc.update("DELETE FROM public.offer_change_log");
        jdbc.update("DELETE FROM public.plan_price_history WHERE changed_by IS NOT NULL");
        jdbc.update("UPDATE public.plan_prices SET amount_xaf = 3500, updated_by = NULL "
                + "WHERE plan_code = 'PRO' AND cycle_code = 'MONTHLY'");
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenant);
        }
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.profile_modules WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.plans WHERE code LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.users WHERE phone_number LIKE '+237699%'");
    }

    private static void rerunV60() throws Exception {
        String sql;
        try (var in = OfferCataloguePostgresIntegrationTest.class
                .getResourceAsStream("/db/migration/V60__offer_catalogue.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private UUID tenant(String plan, String status, String name) {
        UUID id = UUID.randomUUID();
        String code = "KV-" + id.toString().substring(0, 6).toUpperCase();
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, ?, ?, ?, ?)",
                id, code, plan, "kv_" + id.toString().substring(0, 6), status, name);
        tenants.add(id);
        return id;
    }

    private static List<String> grid() {
        return jdbc.queryForList("SELECT plan_code || ':' || cycle_code || ':' || amount_xaf FROM public.plan_prices "
                + "ORDER BY plan_code, cycle_code", String.class);
    }

    private static Discount discount(DiscountScope scope, String label, Instant startsAt, Instant endsAt,
                                     UUID tenant, String code) {
        return new Discount(null, scope, label, new BigDecimal("10"), null, Set.of("PRO"), Set.of(), startsAt, endsAt,
                tenant, code, null, null);
    }

    // ── V60 ──

    @Test
    @DisplayName("seed: cycles Mensuel 1 / Trimestriel 3 / Annuel 12, Bref's grid, one history row per price "
            + "(no author), trial on, payment columns present")
    void seed() {
        assertThat(store.cycles()).extracting(c -> c.code() + ":" + c.label() + ":" + c.months())
                .containsExactly("MONTHLY:Mensuel:1", "QUARTERLY:Trimestriel:3", "ANNUAL:Annuel:12");
        assertThat(grid()).containsExactly("BUSINESS:ANNUAL:79560", "BUSINESS:MONTHLY:8500",
                "BUSINESS:QUARTERLY:23000", "PRO:ANNUAL:32760", "PRO:MONTHLY:3500", "PRO:QUARTERLY:9500",
                "TEAM:ANNUAL:51480", "TEAM:MONTHLY:5500", "TEAM:QUARTERLY:15000");
        List<PriceChange> history = store.priceHistory(null, null, 50);
        assertThat(history).hasSize(9).allSatisfy(h -> {
            assertThat(h.oldAmountXaf()).isNull();
            assertThat(h.changedBy()).isNull();
        });
        assertThat(store.priceHistory("PRO", "ANNUAL", 10)).singleElement()
                .extracting(PriceChange::newAmountXaf).isEqualTo(32_760L);
        assertThat(jdbc.queryForObject("SELECT trial_enabled FROM public.plan_settings", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("V60 run twice: nothing changes (grid, cycles, history)")
    void rerunIsNoOp() throws Exception {
        List<String> grid = grid();
        long history = jdbc.queryForObject("SELECT count(*) FROM public.plan_price_history", Long.class);
        jdbc.update("UPDATE public.billing_cycles SET label = 'Mois' WHERE code = 'MONTHLY'");
        try {
            rerunV60();
            assertThat(grid()).isEqualTo(grid);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.plan_price_history", Long.class))
                    .isEqualTo(history);
            assertThat(store.cycle("MONTHLY").orElseThrow().label()).isEqualTo("Mois"); // an edit is kept
        } finally {
            jdbc.update("UPDATE public.billing_cycles SET label = 'Mensuel' WHERE code = 'MONTHLY'");
        }
    }

    @Test
    @DisplayName("backfill: a legacy payment takes the plan of the paid-kind event recorded with it; none → null")
    void backfill() throws Exception {
        UUID paid = tenant("TEAM", "ACTIVE", "Payeur");
        UUID gifted = tenant("FREE", "ACTIVE", "Sans événement");
        Instant at = Instant.parse("2026-10-01T09:00:00Z");
        jdbc.update("INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at) "
                + "VALUES (?, 'FREE', 'PRO', 'ADMIN_ACTIVATION', ?), (?, 'PRO', 'TEAM', 'ADMIN_ACTIVATION', ?), "
                + "(?, 'TEAM', 'BUSINESS', 'ADMIN_ACTIVATION', ?)",
                paid, Timestamp.from(at.minus(30, ChronoUnit.DAYS)), paid, Timestamp.from(at), paid,
                Timestamp.from(at.plus(10, ChronoUnit.DAYS)));
        for (UUID tenant : List.of(paid, gifted)) {
            jdbc.update("INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                            + "period_end, recorded_by, created_at) VALUES (?, 5500, 'CASH', ?, ?, ?, ?, ?)",
                    tenant, Timestamp.from(at), Date.valueOf(LocalDate.of(2026, 10, 1)),
                    Date.valueOf(LocalDate.of(2026, 10, 30)), UUID.randomUUID(), Timestamp.from(at));
        }

        rerunV60();

        assertThat(jdbc.queryForObject("SELECT plan_code FROM public.tenant_payments WHERE tenant_id = ?",
                String.class, paid)).isEqualTo("TEAM");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM public.tenant_payments WHERE tenant_id = ?",
                String.class, gifted)).isNull();
        assertThat(jdbc.queryForObject("SELECT cycle_code FROM public.tenant_payments WHERE tenant_id = ?",
                String.class, paid)).isNull();
    }

    // ── prices ──

    @Test
    @DisplayName("price write: locked read, upsert, history with the author's name, newest first")
    void priceWrite() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role, first_name, last_name) VALUES (?, now(), now(), true, 0, 'h', "
                + "'+23769900001', 'SUPER_ADMIN', 'Awa', 'Ngono')", actor);

        tx.executeWithoutResult(s -> {
            store.lockPlan("PRO");
            assertThat(store.lockPrice("PRO", "MONTHLY").orElseThrow().amountXaf()).isEqualTo(3_500);
            store.upsertPrice("PRO", "MONTHLY", 4_000, actor);
            store.recordPriceChange("PRO", "MONTHLY", 3_500L, 4_000L, actor);
        });

        assertThat(store.price("PRO", "MONTHLY").orElseThrow().amountXaf()).isEqualTo(4_000);
        PriceChange last = store.priceHistory("PRO", "MONTHLY", 10).get(0);
        assertThat(last.oldAmountXaf()).isEqualTo(3_500);
        assertThat(last.newAmountXaf()).isEqualTo(4_000);
        assertThat(last.changedByName()).isEqualTo("Awa Ngono");
        assertThat(store.lockPrice("FREE", "MONTHLY")).isEmpty();
        assertThatThrownBy(() -> store.upsertPrice("PRO", "MONTHLY", 0, actor))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── discounts ──

    @Test
    @DisplayName("live candidates in one query: promotions live now, the company's own, the typed code "
            + "(case-insensitive); expired, future, archived and other companies' excluded")
    void liveCandidates() {
        Instant now = Instant.now();
        UUID mine = tenant("PRO", "ACTIVE", "Mienne");
        UUID other = tenant("PRO", "ACTIVE", "Autre");
        UUID actor = UUID.randomUUID();
        store.insertDiscount(discount(DiscountScope.PROMOTION, "live", null, now.plus(1, ChronoUnit.DAYS), null,
                null), actor);
        store.insertDiscount(discount(DiscountScope.PROMOTION, "expired", null, now.minusSeconds(5), null, null),
                actor);
        store.insertDiscount(discount(DiscountScope.PROMOTION, "future", now.plus(1, ChronoUnit.HOURS),
                now.plus(2, ChronoUnit.DAYS), null, null), actor);
        store.insertDiscount(discount(DiscountScope.TENANT, "mine", null, null, mine, null), actor);
        store.insertDiscount(discount(DiscountScope.TENANT, "other", null, null, other, null), actor);
        UUID code = store.insertDiscount(discount(DiscountScope.PROMO_CODE, "code", null, null, null, "BREF10"),
                actor);
        UUID archived = store.insertDiscount(discount(DiscountScope.PROMO_CODE, "archived", null, null, null,
                "OLD10"), actor);
        store.updateDiscount(store.discount(archived).orElseThrow().withArchivedAt(now), actor);

        assertThat(store.liveCandidates(mine, "bref10", now)).extracting(Discount::label)
                .containsExactlyInAnyOrder("live", "mine", "code");
        assertThat(store.liveCandidates(null, null, now)).extracting(Discount::label).containsExactly("live");
        assertThat(store.liveCandidates(null, "old10", now)).extracting(Discount::label).containsExactly("live");

        Discount read = store.discount(code).orElseThrow();
        assertThat(read.planCodes()).containsExactly("PRO");
        assertThat(read.percentOff()).isEqualByComparingTo("10");
        assertThat(read.amountOffXaf()).isNull();
        assertThat(store.discounts(false, 500)).hasSize(6);
        assertThat(store.discounts(true, 500)).hasSize(7);
        assertThat(store.discounts(true, 2)).hasSize(2);

        // promo code uniqueness among live codes, whatever the case; an archived code can be reused
        assertThat(store.promoCodeTaken("Bref10", null)).isTrue();
        assertThat(store.promoCodeTaken("BREF10", code)).isFalse();
        assertThat(store.promoCodeTaken("OLD10", null)).isFalse();
        assertThatThrownBy(() -> store.insertDiscount(discount(DiscountScope.PROMO_CODE, "dup", null, null, null,
                "BREF10"), actor)).isInstanceOf(DuplicateKeyException.class);
        // PROMOTION without end refused by the database too
        assertThatThrownBy(() -> store.insertDiscount(discount(DiscountScope.PROMOTION, "no end", null, null, null,
                null), actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ── plans and companies ──

    @Test
    @DisplayName("reorder: one statement, 10 / 20 / 30… in the given order")
    void reorder() {
        List<String> before = jdbc.queryForList("SELECT code FROM public.plans ORDER BY sort_order, code",
                String.class);
        List<String> reversed = new ArrayList<>(before);
        java.util.Collections.reverse(reversed);
        try {
            store.reorderPlans(reversed);
            assertThat(jdbc.queryForList("SELECT code FROM public.plans ORDER BY sort_order", String.class))
                    .isEqualTo(reversed);
            assertThat(jdbc.queryForObject("SELECT sort_order FROM public.plans WHERE code = ?", Integer.class,
                    reversed.get(0))).isEqualTo(10);
        } finally {
            store.reorderPlans(before);
        }
    }

    @Test
    @DisplayName("profile copy: limits, paid-module decisions and quotas of the source plan")
    void copyProfile() {
        jdbc.update("INSERT INTO public.plans (code, label, kind) VALUES ('TEST_COPY', 'Copie', 'PAID')");

        store.copyProfile("PRO", "TEST_COPY");

        String profile = "SELECT concat_ws('/', max_stores, max_products, max_employees, max_employees_per_store) "
                + "FROM public.plan_profiles WHERE plan_type = ?";
        assertThat(jdbc.queryForObject(profile, String.class, "TEST_COPY"))
                .isEqualTo(jdbc.queryForObject(profile, String.class, "PRO"));
        String modules = "SELECT module_id || ':' || granted FROM public.profile_modules WHERE plan_type = ? "
                + "ORDER BY module_id";
        assertThat(jdbc.queryForList(modules, String.class, "TEST_COPY")).isNotEmpty()
                .isEqualTo(jdbc.queryForList(modules, String.class, "PRO"));
        String quotas = "SELECT metric || period || quota_limit FROM public.plan_quotas WHERE plan_type = ? "
                + "ORDER BY 1";
        assertThat(jdbc.queryForList(quotas, String.class, "TEST_COPY")).isNotEmpty()
                .isEqualTo(jdbc.queryForList(quotas, String.class, "PRO"));
    }

    @Test
    @DisplayName("companies: counts per plan exclude deleted ones; lookups by id and schema; names in one query")
    void companies() {
        long before = store.tenantsOnPlan("BUSINESS");
        UUID a = tenant("BUSINESS", "ACTIVE", "Alpha");
        tenant("BUSINESS", "DELETED", "Supprimée");

        assertThat(store.tenantsOnPlan("BUSINESS")).isEqualTo(before + 1);
        assertThat(store.tenantCountsByPlan()).containsEntry("BUSINESS", before + 1);
        assertThat(store.tenant(a)).contains(new TenantPlanRef(a, "BUSINESS"));
        assertThat(store.tenantBySchema("kv_" + a.toString().substring(0, 6)))
                .contains(new TenantPlanRef(a, "BUSINESS"));
        assertThat(store.tenantBySchema("not a schema")).isEmpty();
        assertThat(store.tenantNames(List.of(a))).isEqualTo(Map.of(a, "Alpha"));
    }

    @Test
    @DisplayName("journal: newest first with the author's name; keyset before an instant")
    void journal() throws InterruptedException {
        store.journal(OfferEntity.PLAN, "PRO", "Plan « Pro » archivé", null);
        Thread.sleep(5);
        store.journal(OfferEntity.PRICE, "PRO:MONTHLY", "Prix « Pro » · Mensuel : 3 500 FCFA → 4 000 FCFA", null);

        List<OfferChange> changes = store.changes(null, null, 10);
        assertThat(changes).extracting(OfferChange::entity).containsExactly(OfferEntity.PRICE, OfferEntity.PLAN);
        assertThat(store.changes(changes.get(0).occurredAt(), null, 10)).extracting(OfferChange::summary)
                .containsExactly("Plan « Pro » archivé");
    }

    @Test
    @DisplayName("journal keyset: two lines sharing one instant across a page boundary are both read, once each")
    void journalKeysetSameInstant() {
        Instant at = Instant.parse("2026-11-09T10:00:00Z");
        for (String summary : List.of("A", "B", "C")) {
            jdbc.update("INSERT INTO public.offer_change_log (entity, entity_key, summary, occurred_at) "
                    + "VALUES ('PLAN', 'PRO', ?, ?)", summary, Timestamp.from(at));
        }

        List<OfferChange> first = store.changes(null, null, 2);
        OfferChange last = first.get(1);
        List<OfferChange> second = store.changes(last.occurredAt(), last.id(), 2);

        List<String> all = new ArrayList<>();
        first.forEach(c -> all.add(c.summary()));
        second.forEach(c -> all.add(c.summary()));
        assertThat(all).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("plan insert never overwrites; cycle in use by a price or a payment; prices of one plan")
    void insertPlanAndCycleInUse() {
        com.keevo.shared.capability.entitlement.PlanDefinition plan =
                new com.keevo.shared.capability.entitlement.PlanDefinition(
                        com.keevo.identity.api.PlanType.of("TEST_NEW"), "Nouveau", null, 99, true,
                        com.keevo.identity.api.PlanKind.PAID, null);
        assertThat(store.insertPlan(plan)).isTrue();
        assertThat(store.insertPlan(new com.keevo.shared.capability.entitlement.PlanDefinition(plan.code(),
                "Écrasé", null, 1, true, com.keevo.identity.api.PlanKind.FREE, null))).isFalse();
        assertThat(jdbc.queryForObject("SELECT label || kind FROM public.plans WHERE code = 'TEST_NEW'",
                String.class)).isEqualTo("NouveauPAID");

        assertThat(store.cycleInUse("MONTHLY")).isTrue(); // seeded prices
        jdbc.update("INSERT INTO public.billing_cycles (code, label, months) VALUES ('TEST_CYCLE', 'Test', 2)");
        try {
            assertThat(store.cycleInUse("TEST_CYCLE")).isFalse();
            UUID t = tenant("TEAM", "ACTIVE", "Payeur");
            jdbc.update("INSERT INTO public.tenant_payments (tenant_id, amount_xaf, method, paid_at, period_start, "
                    + "period_end, recorded_by, plan_code, cycle_code) VALUES (?, 5500, 'CASH', now(), current_date, "
                    + "current_date + 60, ?, 'TEAM', 'TEST_CYCLE')", t, UUID.randomUUID());
            assertThat(store.cycleInUse("TEST_CYCLE")).isTrue();
        } finally {
            for (UUID tenant : tenants) {
                jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            }
            jdbc.update("DELETE FROM public.billing_cycles WHERE code = 'TEST_CYCLE'");
        }
        assertThat(store.prices("PRO")).extracting(p -> p.cycleCode())
                .containsExactlyInAnyOrder("MONTHLY", "QUARTERLY", "ANNUAL");
    }
}
