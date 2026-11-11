package com.keevo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 24.5 — the monetisation import end to end, through the super-admin API and the production beans, on a
 * synthetic Bref database (structure of {@code migration/bref-source-schema.sql} + {@code
 * migration/bref-source-monetisation-schema.sql}, random ids, no real data): a Mobile Money owner (plan in force,
 * confirmed and pending checkouts, scheduled period of another plan, credit, SMS recharges, counters), an App Store
 * owner in grace (subscription, processed events), an owner without subscription, an owner with an unknown plan
 * (blocked); re-run, export = database.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("24.5 — Bref monetisation import (plan in force, payments, App Store, credit, packs, counters)")
class BrefMonetisationImportFullContextTest extends FullContextIntegrationTest {

    private static final String BREF_DB = "bref_src_it245";
    private static final Path EXPORT_DIR;
    private static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final Duration DAY = Duration.ofDays(1);

    static {
        SharedPostgresContainer.freshDatabase(BREF_DB);
        try {
            EXPORT_DIR = Files.createTempDirectory("bref-export-it245");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void brefSource(DynamicPropertyRegistry registry) {
        registry.add("keevo.migration.bref.jdbc.url", () -> SharedPostgresContainer.jdbcUrl(BREF_DB));
        registry.add("keevo.migration.bref.jdbc.username", SharedPostgresContainer::username);
        registry.add("keevo.migration.bref.jdbc.password", SharedPostgresContainer::password);
        registry.add("keevo.migration.bref.export-dir", EXPORT_DIR::toString);
    }

    private static JdbcTemplate bref;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<BrefMonetisationSourcePort> monetisationSources;

    private final UUID adminId = UUID.randomUUID();
    /** The test's « now », to the millisecond (Bref instants are read in milliseconds). */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeAll
    static void brefSchema() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgresContainer.jdbcUrl(BREF_DB),
                SharedPostgresContainer.username(), SharedPostgresContainer.password());
        bref = new JdbcTemplate(ds);
        bref.execute(new ClassPathResource("migration/bref-source-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        bref.execute(new ClassPathResource("migration/bref-source-monetisation-schema.sql")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── fixtures (synthetic Bref rows) ───────────────────────────────────────

    private static LocalDateTime utc(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static String tag() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }

    private UUID owner() {
        UUID id = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        bref.update("INSERT INTO \"user\" (id, phone, role, \"isActive\", \"isVerified\", \"createdAt\", "
                + "\"updatedAt\") VALUES (?, ?, 'owner', true, true, ?, ?)", id, phone, utc(now.minus(DAY.multipliedBy(90))),
                utc(now.minus(DAY.multipliedBy(90))));
        UUID shop = UUID.randomUUID();
        bref.update("INSERT INTO shop (id, \"userId\", name, address, category, country, \"createdAt\", \"updatedAt\") "
                + "VALUES (?, ?, ?, 'Adresse', 'electronics', 'CM', ?, ?)", shop, id, "Boutique " + tag(),
                utc(now.minus(DAY.multipliedBy(90))), utc(now.minus(DAY.multipliedBy(90))));
        return id;
    }

    private UUID subscription(UUID owner, String plan, String status, Instant start, Instant end, long paid,
                              long credit, String method, String original, Instant grace) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO subscription (id, \"ownerId\", plan, status, \"billingCycle\", \"startedAt\", "
                        + "\"endsAt\", \"amountPaid\", \"creditUsed\", \"paymentMethod\", "
                        + "\"appleOriginalTransactionId\", \"gracePeriodEndsAt\", \"createdAt\", \"updatedAt\") "
                        + "VALUES (?, ?, ?, ?, 'monthly', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, owner, plan, status, utc(start), utc(end), paid, credit, method, original, utc(grace),
                utc(start), utc(start));
        return id;
    }

    private UUID request(UUID owner, String status, long amount, long credit, long due, Instant start, Instant end) {
        UUID id = UUID.randomUUID();
        bref.update("INSERT INTO plan_payment_request (id, \"ownerId\", \"targetPlan\", \"targetBillingCycle\", amount, "
                        + "\"creditApplied\", \"amountDue\", status, \"paymentMethod\", \"startsAt\", \"endsAt\", "
                        + "\"createdAt\", \"updatedAt\", \"paymentProvider\", \"providerTransactionRef\", "
                        + "\"providerStatus\", \"providerMetadata\") VALUES (?, ?, 'pro', 'monthly', ?, ?, ?, ?, "
                        + "'orange_money', ?, ?, ?, ?, 'mycoolpay', ?, 'SUCCESS', '{\"phone\":\"+237600000000\"}')",
                id, owner, amount, credit, due, status, utc(start), utc(end), utc(start.minusSeconds(120)),
                utc(start), "MCP-" + tag());
        return id;
    }

    private MvcResult importOwner(UUID owner, String source) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/migration/bref/owners/" + owner + "/imports")
                .header("Authorization", "Bearer " + TestAccess.token(jdbc, jwtTokenProvider, adminId, "public",
                        "SUPER_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"" + source + "\"}")).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long number(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private BrefMonetisationSourcePort source(SourceKind kind) {
        return monetisationSources.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mobile Money owner: plan in force, checkouts, ledger with credit part, credit, packs, counters; "
            + "re-run writes nothing; the export gives the same section")
    void mobileMoneyOwner() throws Exception {
        UUID owner = owner();
        Instant start = now.minus(DAY.multipliedBy(5));
        Instant end = now.plus(DAY.multipliedBy(25));
        subscription(owner, "pro", "active", start, end, 3000, 500, "orange_money", null, null);
        UUID confirmed = request(owner, "confirmed", 3500, 500, 3000, start, end);
        UUID pending = request(owner, "pending", 3500, 0, 3500, now.minus(DAY), now.plus(DAY.multipliedBy(29)));
        subscription(owner, "team", "scheduled", end, end.plus(DAY.multipliedBy(30)), 5500, 0, "momo", null, null);
        subscription(owner, "free", "cancelled", now.minus(DAY.multipliedBy(60)), null, 0, 0, "admin_grant", null,
                null);
        bref.update("INSERT INTO account_credit (id, \"ownerId\", amount, \"updatedAt\") VALUES (?, ?, 1750, ?)",
                UUID.randomUUID(), owner, utc(now));
        bref.update("INSERT INTO sms_recharge_request (id, \"userId\", \"smsQuantity\", \"amountDue\", status, "
                + "\"paymentMethod\", phone, \"completedAt\") VALUES (?, ?, 100, 2000, 'completed', 'momo', "
                + "'+237600000000', ?)", UUID.randomUUID(), owner, utc(now));
        bref.update("INSERT INTO sms_recharge_request (id, \"userId\", \"smsQuantity\", \"amountDue\", status, "
                + "\"paymentMethod\") VALUES (?, ?, 50, 1000, 'pending', 'momo')", UUID.randomUUID(), owner);
        String month = YearMonth.from(now.atZone(WAT)).toString();
        String previous = YearMonth.from(now.atZone(WAT)).minusMonths(1).toString();
        for (Object[] c : new Object[][] {{"sms", month, 4}, {"invoices", month, 12}, {"sms_free", "all", 2},
                {"email", previous, 7}}) {
            bref.update("INSERT INTO usage_counter (id, \"ownerId\", metric, \"periodMonth\", used) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), owner, c[0], c[1], c[2]);
        }

        JsonNode batch = data(importOwner(owner, "DATABASE"));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        JsonNode counts = batch.path("counts");
        assertThat(counts.path("plansApplied").asInt()).isEqualTo(1);
        assertThat(counts.path("paymentRequestsImported").asInt()).isEqualTo(2);
        assertThat(counts.path("paymentsRecorded").asInt()).isEqualTo(1);
        assertThat(counts.path("creditImportedXaf").asInt()).isEqualTo(1750 + 5500);
        assertThat(counts.path("smsPackUnitsImported").asInt()).isEqualTo(100);
        assertThat(counts.path("usageCountersImported").asInt()).isEqualTo(3);
        assertThat(batch.path("notes").toString()).contains("PAYMENT_PENDING_EXPIRED", "PLAN_SCHEDULED_TO_CREDIT",
                "SMS_RECHARGE_NOT_PAID", "COUNTER_PAST_PERIOD").doesNotContain("+2376");

        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);
        // plan in force: PRO until Bref's exact end; the registration plan replaced (MIGRATION_BREF), trial rows gone
        Map<String, Object> sub = jdbc.queryForMap("SELECT plan_type, expires_at FROM \"" + schema
                + "\".subscriptions ORDER BY created_at LIMIT 1");
        assertThat(sub.get("plan_type")).isEqualTo("PRO");
        assertThat(((Timestamp) sub.get("expires_at")).toInstant()).isEqualTo(end);
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("PRO");
        assertThat(jdbc.queryForList("SELECT to_plan FROM public.tenant_plan_events WHERE tenant_id = ? AND cause = "
                + "'MIGRATION_BREF' ORDER BY occurred_at", String.class, tenantId)).hasSize(2).last().isEqualTo("PRO");
        assertThat(number("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ? AND status = 'TRIAL'",
                tenantId)).isZero();
        // checkouts: same ids, never in flight; the ledger row with the cash and the credit part
        assertThat(jdbc.queryForList("SELECT id::text || ':' || status || ':' || amount_due_xaf || ':' || "
                + "credit_applied_xaf FROM public.payment_requests WHERE tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder(confirmed + ":CONFIRMED:3000:500", pending + ":EXPIRED:3500:0");
        Map<String, Object> ledger = jdbc.queryForMap("SELECT id, amount_xaf, credit_xaf, method, plan_code, "
                + "cycle_code, payment_request_id FROM public.tenant_payments WHERE tenant_id = ?", tenantId);
        assertThat(ledger).containsEntry("id", confirmed).containsEntry("amount_xaf", 3000).containsEntry("credit_xaf",
                500L).containsEntry("method", "MOBILE_MONEY").containsEntry("plan_code", "PRO")
                .containsEntry("payment_request_id", confirmed);
        // credit to the franc: Bref balance + the scheduled TEAM period
        assertThat(number("SELECT balance_xaf FROM public.tenant_credits WHERE tenant_id = ?", tenantId))
                .isEqualTo(7250);
        assertThat(number("SELECT count(*) FROM public.tenant_credit_movements WHERE tenant_id = ? AND kind = "
                + "'MIGRATION'", tenantId)).isEqualTo(2);
        assertThat(number("SELECT remaining FROM public.usage_credits WHERE tenant_id = ? AND metric = 'SMS'",
                tenantId)).isEqualTo(100);
        assertThat(jdbc.queryForList("SELECT metric || ':' || period || ':' || used FROM public.usage_counters "
                + "WHERE tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder("SMS:MONTH:4", "DOCUMENTS:MONTH:12", "SMS:LIFETIME:2");

        // ── re-run: nothing written
        long records = number("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", owner);
        JsonNode again = data(importOwner(owner, "DATABASE"));
        assertThat(again.path("status").asText()).isEqualTo("COMPLETED");
        for (String key : List.of("plansApplied", "paymentRequestsImported", "paymentsRecorded", "creditImportedXaf",
                "smsPackUnitsImported", "usageCountersImported")) {
            assertThat(again.path("counts").path(key).asInt()).as(key).isZero();
        }
        assertThat(again.path("counts").path("alreadyImported").asLong()).isEqualTo(records);
        assertThat(number("SELECT balance_xaf FROM public.tenant_credits WHERE tenant_id = ?", tenantId))
                .isEqualTo(7250);
        assertThat(number("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ?", tenantId))
                .isEqualTo(2);

        // ── the export of the same owner gives the same section (same checksums) and imports nothing new
        writeExport(owner);
        BrefMonetisation fromDb = source(SourceKind.DATABASE).load(owner, List.of(owner));
        BrefMonetisation fromExport = source(SourceKind.EXPORT).load(owner, List.of(owner));
        assertThat(fromExport.subscriptions().stream().map(BrefMonetisation.Subscription::canonical).toList())
                .containsExactlyInAnyOrderElementsOf(fromDb.subscriptions().stream()
                        .map(BrefMonetisation.Subscription::canonical).toList());
        assertThat(fromExport.paymentRequests().stream().map(BrefMonetisation.PlanPaymentRequest::canonical).toList())
                .containsExactlyInAnyOrderElementsOf(fromDb.paymentRequests().stream()
                        .map(BrefMonetisation.PlanPaymentRequest::canonical).toList());
        assertThat(fromExport.accountCredit().canonical()).isEqualTo(fromDb.accountCredit().canonical());
        assertThat(fromExport.smsRecharges()).hasSameSizeAs(fromDb.smsRecharges());
        assertThat(fromExport.usageCounters().stream().map(BrefMonetisation.UsageCounter::canonical).toList())
                .containsExactlyInAnyOrderElementsOf(fromDb.usageCounters().stream()
                        .map(BrefMonetisation.UsageCounter::canonical).toList());
        JsonNode viaExport = data(importOwner(owner, "EXPORT"));
        assertThat(viaExport.path("status").asText()).as(viaExport.toString()).isEqualTo("COMPLETED");
        assertThat(viaExport.path("counts").path("paymentRequestsImported").asInt()).isZero();
        assertThat(viaExport.path("counts").path("plansApplied").asInt()).isZero();
    }

    @Test
    @DisplayName("App Store owner in grace: plan to the grace end, subscription BILLING_ISSUE, processed events only")
    void appStoreOwnerInGrace() throws Exception {
        UUID owner = owner();
        String original = "2000" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        Instant grace = now.plus(DAY.multipliedBy(10));
        subscription(owner, "business", "active", now.minus(DAY.multipliedBy(32)), now.minus(DAY.multipliedBy(2)), 0,
                0, "apple", original, grace);
        bref.update("INSERT INTO apple_iap_transaction (id, \"ownerId\", \"originalTransactionId\", \"transactionId\", "
                        + "\"productId\", \"bundleId\", environment, \"purchaseDate\", \"expiresDate\") VALUES "
                        + "(?, ?, ?, ?, 'brefpoint.business.monthly', 'com.bref', 'Production', ?, ?)",
                UUID.randomUUID(), owner.toString(), original, "T" + tag(),
                Timestamp.from(now.minus(DAY.multipliedBy(32))), Timestamp.from(now.minus(DAY.multipliedBy(2))));
        String ev1 = "ev-" + tag();
        String ev2 = "ev-" + tag();
        String ev3 = "ev-" + tag();
        bref.update("INSERT INTO revenuecat_webhook_event (id, type, \"subjectKey\", \"eventTimestampMs\", "
                + "\"processedAt\") VALUES (?, 'RENEWAL', ?, 1000, ?), (?, 'BILLING_ISSUE', ?, 2000, ?), "
                + "(?, 'RENEWAL', ?, 3000, NULL)", ev1, original, utc(now), ev2, original, utc(now), ev3, original);

        JsonNode batch = data(importOwner(owner, "DATABASE"));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        assertThat(batch.path("counts").path("appStoreSubscriptionsImported").asInt()).isEqualTo(1);
        assertThat(batch.path("counts").path("appStoreEventsImported").asInt()).isEqualTo(2);
        assertThat(batch.path("notes").toString()).contains("PLAN_IN_GRACE", "REVENUECAT_EVENT_UNPROCESSED");
        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("BUSINESS");
        Map<String, Object> ext = jdbc.queryForMap("SELECT tenant_id, status, grace_expires_at, granted_expires_at, "
                + "last_event_ms, environment, plan_code FROM public.external_subscriptions WHERE "
                + "original_transaction_id = ?", original);
        assertThat(ext).containsEntry("tenant_id", tenantId).containsEntry("status", "BILLING_ISSUE")
                .containsEntry("last_event_ms", 2000L).containsEntry("environment", "PRODUCTION")
                .containsEntry("plan_code", "BUSINESS");
        assertThat(((Timestamp) ext.get("grace_expires_at")).toInstant()).isEqualTo(grace);
        assertThat(((Timestamp) ext.get("granted_expires_at")).toInstant()).isEqualTo(grace);
        assertThat(jdbc.queryForList("SELECT id FROM public.revenuecat_webhook_event WHERE tenant_id = ? AND "
                + "outcome = 'APPLIED'", String.class, tenantId)).containsExactlyInAnyOrder(ev1, ev2);
        // the Apple price is not entered in the catalogue (V62 seed): no ledger row, noted
        assertThat(number("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ?", tenantId)).isZero();
        assertThat(batch.path("notes").toString()).contains("APPLE_PRICE_UNKNOWN");
    }

    @Test
    @DisplayName("no subscription → free plan without expiry; unknown plan in force → blocked, nothing written")
    void freeAndBlocked() throws Exception {
        UUID free = owner();
        JsonNode batch = data(importOwner(free, "DATABASE"));
        assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
        UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
        String freePlan = jdbc.queryForObject("SELECT free_plan_code FROM public.plan_settings LIMIT 1", String.class);
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo(freePlan);
        String schema = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class,
                tenantId);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM \"" + schema + "\".subscriptions ORDER BY created_at "
                + "LIMIT 1", Timestamp.class)).isNull();

        UUID gold = owner();
        subscription(gold, "gold", "active", now.minus(DAY), now.plus(DAY), 1000, 0, "momo", null, null);
        JsonNode blocked = data(importOwner(gold, "DATABASE"));
        assertThat(blocked.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.path("cases").toString()).contains("MONETISATION_INVALID");
        assertThat(number("SELECT count(*) FROM public.migration_records WHERE bref_owner_id = ?", gold)).isZero();
    }

    @Test
    @DisplayName("review: an App Store subscription already bound to another company is not moved, its purchases are "
            + "not counted, the import completes with a note")
    void appStoreSubscriptionHeldElsewhere() throws Exception {
        UUID first = owner();
        JsonNode firstBatch = data(importOwner(first, "DATABASE"));
        UUID otherTenant = UUID.fromString(firstBatch.path("tenantId").asText());
        String original = "3000" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        jdbc.update("INSERT INTO public.external_subscriptions (id, tenant_id, tenant_schema, store, app_user_id, "
                + "original_transaction_id, product_id, plan_code, cycle_code, status, environment) SELECT ?, t.id, "
                + "t.schema_name, 'APP_STORE', ?, ?, 'brefpoint.pro.month', 'PRO', 'MONTHLY', 'ACTIVE', 'PRODUCTION' "
                + "FROM public.tenants t WHERE t.id = ?", UUID.randomUUID(), first, original, otherTenant);
        Long declared = jdbc.queryForObject("SELECT declared_price_xaf FROM public.store_products WHERE product_id = "
                + "'brefpoint.pro.month'", Long.class);
        jdbc.update("UPDATE public.store_products SET declared_price_xaf = 2500 WHERE product_id = 'brefpoint.pro.month'");
        try {
            UUID owner = owner();
            bref.update("INSERT INTO apple_iap_transaction (id, \"ownerId\", \"originalTransactionId\", "
                            + "\"transactionId\", \"productId\", \"bundleId\", environment, \"purchaseDate\", "
                            + "\"expiresDate\") VALUES (?, ?, ?, ?, 'brefpoint.pro.month', 'com.bref', 'Production', ?, ?)",
                    UUID.randomUUID(), owner.toString(), original, "T" + tag(),
                    Timestamp.from(now.minus(DAY.multipliedBy(3))), Timestamp.from(now.plus(DAY.multipliedBy(27))));
            JsonNode batch = data(importOwner(owner, "DATABASE"));
            assertThat(batch.path("status").asText()).as(batch.toString()).isEqualTo("COMPLETED");
            assertThat(batch.path("notes").toString()).contains("APPLE_SUBSCRIPTION_HELD");
            assertThat(batch.path("counts").path("appStoreSubscriptionsImported").asInt()).isZero();
            assertThat(batch.path("counts").path("paymentsRecorded").asInt()).isZero();
            UUID tenantId = UUID.fromString(batch.path("tenantId").asText());
            assertThat(number("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ?", tenantId)).isZero();
            assertThat(jdbc.queryForObject("SELECT tenant_id FROM public.external_subscriptions WHERE "
                    + "original_transaction_id = ?", UUID.class, original)).isEqualTo(otherTenant);
        } finally {
            jdbc.update("UPDATE public.store_products SET declared_price_xaf = ? WHERE product_id = "
                    + "'brefpoint.pro.month'", declared);
        }
    }

    // ── export of an owner (the 24.5 sections built from the synthetic Bref rows) ───────────────────────────

    private void writeExport(UUID owner) throws Exception {
        ObjectNode export = objectMapper.createObjectNode();
        export.put("formatVersion", 1);
        export.set("owner", objectMapper.readTree(bref.queryForObject("SELECT to_jsonb(u)::text FROM \"user\" u "
                + "WHERE id = ?", String.class, owner)));
        ArrayNode shops = export.putArray("shops");
        bref.queryForList("SELECT to_jsonb(s)::text FROM shop s WHERE \"userId\" = ?", String.class, owner)
                .forEach(r -> shops.add(read(r)));
        export.putArray("members");
        section(export, "subscriptions", "SELECT to_jsonb(s)::text FROM subscription s WHERE \"ownerId\" = ?", owner);
        section(export, "planPaymentRequests", "SELECT (to_jsonb(r) - 'providerMetadata')::text FROM "
                + "plan_payment_request r WHERE \"ownerId\" = ?", owner);
        section(export, "appleTransactions", "SELECT to_jsonb(a)::text FROM apple_iap_transaction a WHERE "
                + "\"ownerId\" = ?::text", owner);
        section(export, "smsRechargeRequests", "SELECT (to_jsonb(r) - 'phone' - 'cardNumber')::text FROM "
                + "sms_recharge_request r WHERE \"userId\" = ?", owner);
        section(export, "usageCounters", "SELECT to_jsonb(u)::text FROM usage_counter u WHERE \"ownerId\" = ?", owner);
        export.set("accountCredit", read(bref.queryForObject("SELECT to_jsonb(c)::text FROM account_credit c WHERE "
                + "\"ownerId\" = ?", String.class, owner)));
        byte[] bytes = objectMapper.writeValueAsBytes(export);
        Files.write(EXPORT_DIR.resolve("owner-" + owner + ".json"), bytes);
        Files.writeString(EXPORT_DIR.resolve("owner-" + owner + ".json.sha256"),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void section(ObjectNode export, String name, String sql, UUID owner) {
        ArrayNode rows = export.putArray(name);
        bref.queryForList(sql, String.class, owner).forEach(r -> rows.add(read(r)));
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
