package com.keevo.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.payments.appstore.adapter.out.revenuecat.InMemoryRevenueCatClient;
import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice;
import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice.Kind;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;
import com.keevo.payments.appstore.domain.port.out.RevenueCatClient.StoreSubscription;
import com.keevo.payments.checkout.domain.model.PlanPeriod;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 21.2 — App Store purchases end to end in the real application context (RevenueCat simulated in memory, never
 * called): authenticated webhook, purchase / renewal / replay / stale / expiration, sandbox filter and review account,
 * tenant resolution (single company, attribute, several companies), unknown user / product / store, cancellation,
 * refund, billing issue with and without grace, expiration after a longer Mobile Money payment, return to a running
 * Mobile Money period on expiration (correctif post-test appareil du 10/11/2026), TRANSFER, concurrent
 * duplicates, owner sync, owner products, and the super-admin App Store gap. Each test cleans its rows.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Payments — App Store purchases through RevenueCat, full context (Story 21.2)")
class AppStoreFullContextTest extends FullContextIntegrationTest {

    private static final String WEBHOOK = "/api/v1/payments/webhook/revenuecat";
    private static final String SECRET = "test-revenuecat-secret";
    private static final String PRO_MONTH = "brefpoint.pro.month";
    private static final String TEAM_MONTH = "brefpoint.team.monthly";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired RevenueCatClient revenueCatClient;
    @Autowired com.keevo.shared.infrastructure.web.RateLimitFilter rateLimit;
    @Autowired ApplicationEvents events;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final String prefix = "T-" + UUID.randomUUID() + "-";
    private final List<UUID> tenants = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String adminToken;
    private long clockMs = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        clearRateLimit();
        fake().clear();
        schema = newTenant();
        tenantId = tenantIdOf(schema);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.store_products WHERE product_id IN (?, ?)",
                Integer.class, PRO_MONTH, TEAM_MONTH)).as("Bref products seeded by V62").isEqualTo(2);
    }

    @AfterEach
    void cleanUp() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.payment_requests WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.external_subscriptions WHERE tenant_id = ?", tenant);
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
        }
        jdbc.update("DELETE FROM public.revenuecat_webhook_event WHERE id LIKE ?", prefix + "%");
        jdbc.update("UPDATE public.store_products SET declared_price_xaf = NULL, observed_price = NULL, "
                + "observed_currency = NULL, observed_price_xaf = NULL, observed_at = NULL, active = TRUE "
                + "WHERE product_id LIKE 'brefpoint.%'");
        jdbc.update("DELETE FROM public.store_products WHERE product_id LIKE 'test.%'");
        jdbc.update("DELETE FROM public.offer_change_log WHERE actor_id = ?", adminId);
        for (UUID user : users) {
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", user);
            jdbc.update("DELETE FROM public.users WHERE id = ?", user);
        }
        fake().clear();
        catalogStore.invalidate();
        clearRateLimit();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InMemoryRevenueCatClient fake() {
        return (InMemoryRevenueCatClient) revenueCatClient;
    }

    private void clearRateLimit() {
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(rateLimit, "buckets")).clear();
    }

    private String newTenant() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenants.add(tenantIdOf(s));
        return s;
    }

    private UUID tenantIdOf(String s) {
        return jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s);
    }

    private String codeOf(String s) {
        return jdbc.queryForObject("SELECT code FROM public.tenants WHERE schema_name = ?", String.class, s);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return objectMapper.readTree(body(result)).path("data");
    }

    private JsonNode error(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(status);
        return objectMapper.readTree(body(result));
    }

    /** A RevenueCat event body (PRODUCTION, App Store, the owner, 2 500 XAF), with overrides. */
    private Map<String, Object> event(String id, String type, String original, String product, Instant expires,
                                      Map<String, Object> extra) {
        Map<String, Object> e = new HashMap<>();
        e.put("id", prefix + id);
        e.put("type", type);
        e.put("app_user_id", ownerId.toString());
        e.put("original_transaction_id", original);
        e.put("transaction_id", original + "-" + id);
        e.put("product_id", product);
        e.put("store", "APP_STORE");
        e.put("environment", "PRODUCTION");
        e.put("event_timestamp_ms", clockMs += 1000);
        e.put("purchased_at_ms", System.currentTimeMillis());
        if (expires != null) {
            e.put("expiration_at_ms", expires.toEpochMilli());
        }
        e.put("price_in_purchased_currency", 2500);
        e.put("currency", "XAF");
        e.putAll(extra);
        return Map.of("api_version", "1.0", "event", e);
    }

    private int send(Map<String, Object> body) throws Exception {
        return sendWith(SECRET, body);
    }

    private int sendWith(String secret, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder r = post(WEBHOOK).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (secret != null) {
            r.header("Authorization", secret);
        }
        return mockMvc.perform(r).andReturn().getResponse().getStatus();
    }

    private String outcome(String id) {
        List<String> rows = jdbc.queryForList("SELECT outcome FROM public.revenuecat_webhook_event WHERE id = ?",
                String.class, prefix + id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String plan(String s) {
        return jdbc.queryForObject("SELECT plan_type FROM \"" + s + "\".subscriptions", String.class);
    }

    private Instant planExpiry(String s) {
        Timestamp t = jdbc.queryForObject("SELECT expires_at FROM \"" + s + "\".subscriptions", Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    private int ledgerRows(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ? "
                + "AND method = 'APP_STORE' AND voided_at IS NULL", Integer.class, tenant);
    }

    private int planEvents(UUID tenant, String cause) {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? AND cause = ?",
                Integer.class, tenant, cause);
    }

    private String subStatus(String original) {
        return jdbc.queryForObject("SELECT status FROM public.external_subscriptions WHERE original_transaction_id = ?",
                String.class, original);
    }

    private List<AppStoreSubscriptionNotice.Kind> notices() {
        return events.stream(AppStoreSubscriptionNotice.class).map(AppStoreSubscriptionNotice::kind).toList();
    }

    private static String original() {
        return "OT-" + UUID.randomUUID();
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private UUID ownerUser(String s, String email) {
        UUID id = UUID.randomUUID();
        users.add(id);
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, email, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?, 'OWNER')", id,
                "+2376" + String.format("%08d", (int) (Math.random() * 100_000_000)), email);
        TestAccess.grant(jdbc, id, s, "OWNER");
        return id;
    }

    // ── purchase lifecycle ───────────────────────────────────────────────────

    @Test
    @DisplayName("INITIAL_PURCHASE → PRO until Apple's expiry, ledger APP_STORE, plan event; replay → nothing; "
            + "RENEWAL → expiry moved + second row; stale → ignored; EXPIRATION → free plan; admin label")
    void lifecycle() throws Exception {
        String ot = original();
        Instant first = days(30);
        assertThat(send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, first, Map.of()))).isEqualTo(200);
        assertThat(outcome("1")).isEqualTo("APPLIED");
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(planExpiry(schema)).isEqualTo(first);
        assertThat(ledgerRows(tenantId)).isEqualTo(1);
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(1);
        Map<String, Object> ledger = jdbc.queryForMap("SELECT amount_xaf, plan_code, cycle_code, recorded_by, note "
                + "FROM public.tenant_payments WHERE tenant_id = ?", tenantId);
        assertThat(ledger).containsEntry("amount_xaf", 2500).containsEntry("plan_code", "PRO")
                .containsEntry("cycle_code", "MONTHLY").containsEntry("recorded_by", ownerId)
                .containsEntry("note", "Paiement en ligne — App Store");
        assertThat(jdbc.queryForObject("SELECT observed_price_xaf FROM public.store_products WHERE product_id = ?",
                Long.class, PRO_MONTH)).isEqualTo(2500L);
        assertThat(notices()).containsExactly(Kind.ACTIVE);
        // the plan's bundle follows (paid-module rows of PRO, as the Mobile Money path of 21.1)
        JsonNode offers = ok(call(get("/api/v1/subscription/offers"), ownerToken, null));
        List<String> proModules = new ArrayList<>();
        for (JsonNode p : offers.path("plans")) {
            if ("PRO".equals(p.path("code").asText())) {
                p.path("modules").forEach(m -> proModules.add(m.path("moduleId").asText()));
            }
        }
        assertThat(jdbc.queryForList("SELECT module_id FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND origin = 'PLAN'", String.class, tenantId)).containsAll(proModules);

        // replay of the same event id
        assertThat(send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, first, Map.of()))).isEqualTo(200);
        assertThat(ledgerRows(tenantId)).isEqualTo(1);
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(1);

        long staleTs = clockMs - 500; // older than the purchase
        Instant second = days(60);
        assertThat(send(event("2", "RENEWAL", ot, PRO_MONTH, second, Map.of()))).isEqualTo(200);
        assertThat(planExpiry(schema)).isEqualTo(second);
        assertThat(ledgerRows(tenantId)).isEqualTo(2);
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(2);

        assertThat(send(event("3", "EXPIRATION", ot, PRO_MONTH, days(1), Map.of("event_timestamp_ms", staleTs))))
                .isEqualTo(200);
        assertThat(outcome("3")).isEqualTo("STALE");
        assertThat(plan(schema)).isEqualTo("PRO");

        JsonNode view = ok(call(get("/api/v1/payments/app-store/subscription"), ownerToken, null));
        assertThat(view.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.path("planLabel").asText()).isEqualTo("Pro");
        assertThat(view.path("cycleLabel").asText()).isEqualTo("Mensuel");
        assertThat(view.path("message").asText()).startsWith("Votre plan Pro est actif et se renouvelle le ");

        assertThat(send(event("4", "EXPIRATION", ot, PRO_MONTH, second, Map.of()))).isEqualTo(200);
        assertThat(plan(schema)).isEqualTo("FREE");
        assertThat(planEvents(tenantId, "EXPIRY")).isEqualTo(1);
        assertThat(subStatus(ot)).isEqualTo("EXPIRED");
        assertThat(notices()).containsExactly(Kind.ACTIVE, Kind.ACTIVE, Kind.EXPIRED);

        JsonNode list = ok(call(get("/api/v1/admin/tenants/" + tenantId + "/payments"), adminToken, null));
        assertThat(list.get(0).path("recordedByLabel").asText()).isEqualTo("Paiement en ligne (App Store)");
        assertThat(list.get(0).path("method").asText()).isEqualTo("APP_STORE");
        assertThat(list.get(0).path("online").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("wrong, missing secret → 401 and nothing stored; TEST event → recorded")
    void authentication() throws Exception {
        String ot = original();
        String before = plan(schema);
        assertThat(sendWith("wrong", event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, days(30), Map.of())))
                .isEqualTo(401);
        assertThat(sendWith(null, event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, days(30), Map.of())))
                .isEqualTo(401);
        assertThat(outcome("1")).isNull();
        assertThat(plan(schema)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.external_subscriptions WHERE "
                + "original_transaction_id = ?", Integer.class, ot)).isZero();
        assertThat(send(event("2", "TEST", null, null, null, Map.of()))).isEqualTo(200);
        assertThat(outcome("2")).isEqualTo("TEST");
    }

    @Test
    @DisplayName("sandbox ignored in this environment; the review account's sandbox purchase is applied")
    void sandbox() throws Exception {
        String ot = original();
        assertThat(send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, days(30),
                Map.of("environment", "SANDBOX")))).isEqualTo(200);
        assertThat(outcome("1")).isEqualTo("IGNORED_SANDBOX");
        assertThat(plan(schema)).isNotEqualTo("PRO");

        String other = newTenant();
        UUID reviewer = ownerUser(other, "review@keevo.test");
        assertThat(send(event("2", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30),
                Map.of("environment", "SANDBOX", "app_user_id", reviewer.toString())))).isEqualTo(200);
        assertThat(outcome("2")).isEqualTo("APPLIED");
        assertThat(plan(other)).isEqualTo("PRO");
    }

    @Test
    @DisplayName("unknown or anonymous user, unknown product, other store → 200, recorded, nothing applied")
    void ignored() throws Exception {
        assertThat(send(event("1", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30),
                Map.of("app_user_id", UUID.randomUUID().toString())))).isEqualTo(200);
        assertThat(outcome("1")).isEqualTo("UNKNOWN_USER");
        assertThat(send(event("2", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30),
                Map.of("app_user_id", "$RCAnonymousID:x")))).isEqualTo(200);
        assertThat(outcome("2")).isEqualTo("UNKNOWN_USER");
        assertThat(send(event("3", "INITIAL_PURCHASE", original(), "unknown.product", days(30), Map.of())))
                .isEqualTo(200);
        assertThat(outcome("3")).isEqualTo("UNKNOWN_PRODUCT");
        assertThat(send(event("4", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30),
                Map.of("store", "PLAY_STORE")))).isEqualTo(200);
        assertThat(outcome("4")).isEqualTo("IGNORED_STORE");
        assertThat(plan(schema)).isNotEqualTo("PRO");
        assertThat(ledgerRows(tenantId)).isZero();
    }

    @Test
    @DisplayName("owner of two companies: no attribute → UNRESOLVED; keevo_tenant attribute → that company")
    void severalCompanies() throws Exception {
        String second = newTenant();
        TestAccess.grant(jdbc, ownerId, second, "OWNER");
        assertThat(send(event("1", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30), Map.of()))).isEqualTo(200);
        assertThat(outcome("1")).isEqualTo("UNRESOLVED");
        assertThat(send(event("2", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30),
                Map.of("subscriber_attributes", Map.of("keevo_tenant", Map.of("value", codeOf(second)))))))
                .isEqualTo(200);
        assertThat(outcome("2")).isEqualTo("APPLIED");
        assertThat(plan(second)).isEqualTo("PRO");
        assertThat(plan(schema)).isNotEqualTo("PRO");
    }

    @Test
    @DisplayName("CANCELLATION keeps the plan; UNCANCELLATION active again; refund → revoked, free plan, row voided")
    void cancellationAndRefund() throws Exception {
        String ot = original();
        Instant exp = days(30);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        assertThat(send(event("2", "CANCELLATION", ot, PRO_MONTH, exp, Map.of("cancel_reason", "UNSUBSCRIBE"))))
                .isEqualTo(200);
        assertThat(subStatus(ot)).isEqualTo("CANCELLED");
        assertThat(plan(schema)).isEqualTo("PRO");
        JsonNode view = ok(call(get("/api/v1/payments/app-store/subscription"), ownerToken, null));
        assertThat(view.path("autoRenew").asBoolean()).isFalse();
        assertThat(view.path("statusLabel").asText()).isEqualTo("Renouvellement automatique désactivé");

        send(event("3", "UNCANCELLATION", ot, PRO_MONTH, exp, Map.of()));
        assertThat(subStatus(ot)).isEqualTo("ACTIVE");

        assertThat(send(event("4", "CANCELLATION", ot, PRO_MONTH, Instant.now(),
                Map.of("cancel_reason", "CUSTOMER_SUPPORT", "transaction_id", ot + "-1")))).isEqualTo(200);
        assertThat(subStatus(ot)).isEqualTo("REVOKED");
        assertThat(plan(schema)).isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT void_reason FROM public.tenant_payments WHERE store_transaction_id = ?",
                String.class, ot + "-1")).isEqualTo("Remboursé par Apple");
    }

    @Test
    @DisplayName("BILLING_ISSUE with Apple grace → plan runs to the grace end; without grace → plan untouched")
    void billingIssue() throws Exception {
        String ot = original();
        Instant exp = days(3);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        send(event("2", "BILLING_ISSUE", ot, PRO_MONTH, exp, Map.of()));
        assertThat(subStatus(ot)).isEqualTo("BILLING_ISSUE");
        assertThat(planExpiry(schema)).isEqualTo(exp);
        Instant grace = days(16);
        send(event("3", "BILLING_ISSUE", ot, PRO_MONTH, exp, Map.of("grace_period_expiration_at_ms",
                grace.toEpochMilli())));
        assertThat(planExpiry(schema)).isEqualTo(grace);
        JsonNode view = ok(call(get("/api/v1/payments/app-store/subscription"), ownerToken, null));
        assertThat(view.path("message").asText()).contains("mettez à jour votre moyen de paiement avant le");
    }

    @Test
    @DisplayName("EXPIRATION after a longer Mobile Money payment of the same plan → plan kept")
    void expirationAfterMobileMoney() throws Exception {
        String ot = original();
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, days(10), Map.of()));
        Instant momo = days(40);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = ?", Timestamp.from(momo));
        send(event("2", "EXPIRATION", ot, PRO_MONTH, days(10), Map.of()));
        assertThat(subStatus(ot)).isEqualTo("EXPIRED");
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(planExpiry(schema)).isEqualTo(momo);
    }

    // ── return to a running Mobile Money period (correctif post-test appareil du 10/11/2026) ──────────────────

    private static LocalDate todayWat() {
        return LocalDate.now(com.keevo.shared.domain.time.KeevoZones.WAT);
    }

    /** A CONFIRMED Mobile Money plan payment (21.1) of {@code plan} covering {@code start..end}, created at {@code at}. */
    private void mobileMoney(String plan, LocalDate start, LocalDate end, Instant at) {
        jdbc.update("INSERT INTO public.payment_requests (id, tenant_id, tenant_schema, requested_by, provider, method, "
                + "phone, plan_code, cycle_code, plan_label, cycle_label, months, base_xaf, reduction_xaf, "
                + "amount_due_xaf, priced_at, status, next_step, app_transaction_ref, period_start, period_end, "
                + "expires_at, confirmed_at, created_at) VALUES (?, ?, ?, ?, 'SANDBOX', 'MTN_MOMO', '+237670000000', ?, "
                + "'MONTHLY', ?, 'Mensuel', 1, 5000, 0, 5000, ?, 'CONFIRMED', 'NONE', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, schema, ownerId, plan, plan, Timestamp.from(at),
                "KVT-" + UUID.randomUUID(), java.sql.Date.valueOf(start), java.sql.Date.valueOf(end),
                Timestamp.from(PlanPeriod.expiryOf(end)), Timestamp.from(at), Timestamp.from(at));
    }

    /** The company is on {@code plan} until {@code until} (what the 21.1 confirmation left). */
    private void onPlan(String plan, Instant until) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, expires_at = ?", plan,
                Timestamp.from(until));
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", plan, tenantId);
    }

    @Test
    @DisplayName("Mobile Money TEAM running, then App Store PRO, then Apple EXPIRATION → back to TEAM until the last "
            + "paid day 23:59:59 WAT (the latest confirmed payment, not an older one ending later), never the free plan")
    void expirationResumesMobileMoney() throws Exception {
        LocalDate end = todayWat().plusDays(40);
        // an older payment of another plan ending even later: ended by the TEAM payment (21.1, ADR-06), never restored
        mobileMoney("PRO", todayWat().minusDays(20), todayWat().plusDays(90), Instant.now().minus(20, ChronoUnit.DAYS));
        mobileMoney("TEAM", todayWat().minusDays(2), end, Instant.now().minus(2, ChronoUnit.DAYS));
        onPlan("TEAM", PlanPeriod.expiryOf(end));

        String ot = original();
        Instant apple = days(10);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, apple, Map.of()));
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(planExpiry(schema)).isEqualTo(apple);

        send(event("2", "EXPIRATION", ot, PRO_MONTH, apple, Map.of()));
        assertThat(outcome("2")).isEqualTo("APPLIED");
        assertThat(subStatus(ot)).isEqualTo("EXPIRED");
        assertThat(plan(schema)).isEqualTo("TEAM");
        assertThat(planExpiry(schema)).isEqualTo(PlanPeriod.expiryOf(end));
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("TEAM");
        assertThat(planEvents(tenantId, "EXPIRY")).isZero();
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(2); // Apple grant + return to TEAM
        assertThat(jdbc.queryForObject("SELECT reason FROM public.tenant_plan_events WHERE tenant_id = ? "
                + "ORDER BY occurred_at DESC LIMIT 1", String.class, tenantId))
                .isEqualTo("Fin de l'abonnement App Store : retour à la période payée par Mobile Money");
        assertThat(notices()).containsExactly(Kind.ACTIVE, Kind.EXPIRED);
    }

    @Test
    @DisplayName("device case: Pro paid by Mobile Money, then App Store Pro ending one day later, then an early Apple "
            + "EXPIRATION → Pro until the last Mobile Money day")
    void expirationResumesMobileMoneyOfSamePlan() throws Exception {
        LocalDate end = todayWat().plusDays(29);
        mobileMoney("PRO", todayWat(), end, Instant.now().minus(1, ChronoUnit.HOURS));
        onPlan("PRO", PlanPeriod.expiryOf(end));

        String ot = original();
        Instant apple = PlanPeriod.expiryOf(end).plus(1, ChronoUnit.DAYS);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, apple, Map.of()));
        assertThat(planExpiry(schema)).isEqualTo(apple);

        send(event("2", "EXPIRATION", ot, PRO_MONTH, apple, Map.of()));
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(planExpiry(schema)).isEqualTo(PlanPeriod.expiryOf(end));
        assertThat(planEvents(tenantId, "EXPIRY")).isZero();
    }

    @Test
    @DisplayName("Mobile Money period ended yesterday, then Apple EXPIRATION → free plan")
    void expirationAfterEndedMobileMoney() throws Exception {
        mobileMoney("TEAM", todayWat().minusDays(31), todayWat().minusDays(1), Instant.now().minus(31,
                ChronoUnit.DAYS));
        String ot = original();
        Instant apple = days(10);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, apple, Map.of()));
        send(event("2", "EXPIRATION", ot, PRO_MONTH, apple, Map.of()));
        assertThat(plan(schema)).isEqualTo("FREE");
        assertThat(planEvents(tenantId, "EXPIRY")).isEqualTo(1);
    }

    @Test
    @DisplayName("no Mobile Money payment, then Apple EXPIRATION → free plan")
    void expirationWithoutMobileMoney() throws Exception {
        String ot = original();
        Instant apple = days(10);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, apple, Map.of()));
        send(event("2", "EXPIRATION", ot, PRO_MONTH, apple, Map.of()));
        assertThat(plan(schema)).isEqualTo("FREE");
        assertThat(planEvents(tenantId, "EXPIRY")).isEqualTo(1);
    }

    @Test
    @DisplayName("App Store PRO, then a Mobile Money TEAM payment took over, then Apple EXPIRATION → TEAM untouched")
    void expirationAfterAnotherPlanTookOver() throws Exception {
        String ot = original();
        Instant apple = days(10);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, apple, Map.of()));
        LocalDate end = todayWat().plusDays(29);
        mobileMoney("TEAM", todayWat(), end, Instant.now());
        onPlan("TEAM", PlanPeriod.expiryOf(end));
        int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ?",
                Integer.class, tenantId);

        send(event("2", "EXPIRATION", ot, PRO_MONTH, apple, Map.of()));
        assertThat(subStatus(ot)).isEqualTo("EXPIRED");
        assertThat(plan(schema)).isEqualTo("TEAM");
        assertThat(planExpiry(schema)).isEqualTo(PlanPeriod.expiryOf(end));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("TRANSFER: the old customer loses it (free plan), the new one's company gets it; RevenueCat down → 503")
    void transfer() throws Exception {
        String ot = original();
        Instant exp = days(30);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        String other = newTenant();
        UUID newOwner = ownerUser(other, null);
        fake().put(ownerId.toString(), List.of());
        fake().put(newOwner.toString(), List.of(new StoreSubscription(ot, PRO_MONTH, "APP_STORE", true, exp, false)));

        fake().failNext();
        Map<String, Object> transfer = event("2", "TRANSFER", null, null, null,
                Map.of("transferred_from", List.of(ownerId.toString()), "transferred_to",
                        List.of(newOwner.toString())));
        assertThat(send(transfer)).isEqualTo(503);
        assertThat(outcome("2")).isNull();

        assertThat(send(transfer)).isEqualTo(200);
        assertThat(outcome("2")).isEqualTo("APPLIED");
        assertThat(plan(schema)).isEqualTo("FREE");
        assertThat(plan(other)).isEqualTo("PRO");
        assertThat(notices()).contains(Kind.TRANSFERRED);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM public.external_subscriptions WHERE "
                + "original_transaction_id = ?", UUID.class, ot)).isEqualTo(tenantIdOf(other));
    }

    @Test
    @DisplayName("the same event delivered twice at once → one state change, one ledger row, one plan event")
    void concurrentDuplicates() throws Exception {
        Map<String, Object> body = event("1", "INITIAL_PURCHASE", original(), PRO_MONTH, days(30), Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            start.await();
            return send(body);
        };
        Future<Integer> a = pool.submit(call);
        Future<Integer> b = pool.submit(call);
        start.countDown();
        assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();
        assertThat(ledgerRows(tenantId)).isEqualTo(1);
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("ledger: USD purchase → the declared Apple price; EUR → fixed peg and note; trial → no row")
    void ledgerCurrencies() throws Exception {
        jdbc.update("UPDATE public.store_products SET declared_price_xaf = 3000 WHERE product_id = ?", PRO_MONTH);
        String usd = original();
        send(event("1", "INITIAL_PURCHASE", usd, PRO_MONTH, days(30), Map.of("price_in_purchased_currency", 4.99,
                "currency", "USD")));
        assertThat(jdbc.queryForObject("SELECT amount_xaf FROM public.tenant_payments WHERE store_transaction_id = ?",
                Integer.class, usd + "-1")).isEqualTo(3000);
        String eur = original();
        send(event("2", "RENEWAL", eur, PRO_MONTH, days(31), Map.of("price_in_purchased_currency", 4.99,
                "currency", "EUR")));
        Map<String, Object> row = jdbc.queryForMap("SELECT amount_xaf, note FROM public.tenant_payments "
                + "WHERE store_transaction_id = ?", eur + "-2");
        assertThat(row).containsEntry("amount_xaf", 3273).containsEntry("note", "Paiement en ligne — App Store (4,99 EUR)");
        String trial = original();
        send(event("3", "INITIAL_PURCHASE", trial, PRO_MONTH, days(32), Map.of("period_type", "TRIAL")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_payments WHERE store_transaction_id = ?",
                Integer.class, trial + "-3")).isZero();
    }

    @Test
    @DisplayName("a purchase left UNRESOLVED (two companies) is recorded in the ledger when the owner's sync binds it")
    void unresolvedThenSync() throws Exception {
        String second = newTenant();
        TestAccess.grant(jdbc, ownerId, second, "OWNER");
        String ot = original();
        Instant exp = days(30);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        assertThat(outcome("1")).isEqualTo("UNRESOLVED");
        fake().put(ownerId.toString(), List.of(new StoreSubscription(ot, PRO_MONTH, "APP_STORE", true, exp, false)));
        ok(call(post("/api/v1/payments/app-store/sync"), ownerToken, null));
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(ledgerRows(tenantId)).isEqualTo(1);
        assertThat(outcome("1")).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("REFUND_REVERSED → active again, plan granted, ledger row restored; PRODUCT_CHANGE → no change")
    void refundReversedAndProductChange() throws Exception {
        String ot = original();
        Instant exp = days(30);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        send(event("2", "CANCELLATION", ot, PRO_MONTH, Instant.now(), Map.of("cancel_reason", "CUSTOMER_SUPPORT",
                "transaction_id", ot + "-1")));
        assertThat(plan(schema)).isEqualTo("FREE");
        send(event("3", "REFUND_REVERSED", ot, PRO_MONTH, exp, Map.of("transaction_id", ot + "-1")));
        assertThat(subStatus(ot)).isEqualTo("ACTIVE");
        assertThat(plan(schema)).isEqualTo("PRO");
        assertThat(ledgerRows(tenantId)).isEqualTo(1);
        send(event("4", "PRODUCT_CHANGE", ot, PRO_MONTH, exp, Map.of("new_product_id", TEAM_MONTH)));
        assertThat(outcome("4")).isEqualTo("APPLIED");
        assertThat(plan(schema)).isEqualTo("PRO");
    }

    @Test
    @DisplayName("TRANSFER while RevenueCat still shows the old customer's subscription → old company keeps it")
    void transferStillActive() throws Exception {
        String ot = original();
        Instant exp = days(30);
        send(event("1", "INITIAL_PURCHASE", ot, PRO_MONTH, exp, Map.of()));
        fake().put(ownerId.toString(), List.of(new StoreSubscription(ot, PRO_MONTH, "APP_STORE", true, exp, false)));
        UUID nobody = UUID.randomUUID();
        fake().put(nobody.toString(), List.of());
        send(event("2", "TRANSFER", null, null, null, Map.of("transferred_from", List.of(ownerId.toString()),
                "transferred_to", List.of(nobody.toString()))));
        assertThat(outcome("2")).isEqualTo("APPLIED");
        assertThat(subStatus(ot)).isEqualTo("ACTIVE");
        assertThat(plan(schema)).isEqualTo("PRO");
    }

    // ── owner endpoints ──────────────────────────────────────────────────────

    @Test
    @DisplayName("sync: active App Store subscription → bound and granted; nothing → 404; RevenueCat error → 502; "
            + "unmapped → 409; bound to another company → 409; store member → 403")
    void sync() throws Exception {
        error(call(post("/api/v1/payments/app-store/sync"), ownerToken, null), 404);
        fake().put(ownerId.toString(), List.of(new StoreSubscription("OT-unmapped", "unknown.product", "APP_STORE",
                true, days(30), false)));
        error(call(post("/api/v1/payments/app-store/sync"), ownerToken, null), 409);

        String ot = original();
        Instant exp = days(30);
        fake().put(ownerId.toString(), List.of(new StoreSubscription(ot, TEAM_MONTH, "APP_STORE", true, exp, false)));
        fake().failNext();
        JsonNode failed = error(call(post("/api/v1/payments/app-store/sync"), ownerToken, null), 502);
        assertThat(failed.path("error").asText()).isEqualTo("La vérification de l'achat App Store a échoué. Réessayez.");

        JsonNode view = ok(call(post("/api/v1/payments/app-store/sync"), ownerToken, null));
        assertThat(view.path("planCode").asText()).isEqualTo("TEAM");
        assertThat(view.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(plan(schema)).isEqualTo("TEAM");
        assertThat(planExpiry(schema)).isEqualTo(exp);
        // the webhook of the same purchase arriving after the sync: no second plan event
        send(event("1", "INITIAL_PURCHASE", ot, TEAM_MONTH, exp, Map.of()));
        assertThat(planEvents(tenantId, "SELF_SERVICE_PAYMENT")).isEqualTo(1);
        assertThat(notices()).containsExactly(Kind.ACTIVE);
        assertThat(ledgerRows(tenantId)).isEqualTo(1);

        String other = newTenant();
        String otherToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, other, "OWNER");
        JsonNode elsewhere = error(call(post("/api/v1/payments/app-store/sync"), otherToken, null), 409);
        assertThat(elsewhere.path("error").asText()).isEqualTo("Cet abonnement App Store est déjà rattaché à une "
                + "autre entreprise");

        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ndiaye', false)", managerId, storeId);
        String member = TestAccess.token(jdbc, jwtTokenProvider, managerId, schema, "MANAGER", "ACTIVE", storeId,
                false);
        assertThat(error(call(post("/api/v1/payments/app-store/sync"), member, null), 403).path("error").asText())
                .isEqualTo("Seul le propriétaire peut payer l'abonnement");
        error(call(get("/api/v1/payments/app-store/subscription"), adminToken, null), 403);
    }

    @Test
    @DisplayName("products: active mapped products of live paid plans; an inactive product is hidden")
    void products() throws Exception {
        JsonNode list = ok(call(get("/api/v1/payments/app-store/products"), ownerToken, null));
        assertThat(list.findValuesAsText("productId")).contains(PRO_MONTH, TEAM_MONTH);
        jdbc.update("UPDATE public.store_products SET active = FALSE WHERE product_id = ?", TEAM_MONTH);
        JsonNode after = ok(call(get("/api/v1/payments/app-store/products"), ownerToken, null));
        assertThat(after.findValuesAsText("productId")).contains(PRO_MONTH).doesNotContain(TEAM_MONTH);
        JsonNode none = ok(call(get("/api/v1/payments/app-store/subscription"), ownerToken, null));
        assertThat(none.isNull() || none.isMissingNode()).isTrue();
    }

    // ── super-admin ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("admin: Apple price declared → gap with the grid in the catalogue; validation in French; journal")
    void gap() throws Exception {
        Long grid = jdbc.queryForObject("SELECT amount_xaf FROM public.plan_prices WHERE plan_code = 'PRO' "
                + "AND cycle_code = 'MONTHLY'", Long.class);
        JsonNode saved = ok(call(put("/api/v1/admin/offers/store-products/" + PRO_MONTH), adminToken,
                Map.of("planCode", "PRO", "cycleCode", "MONTHLY", "declaredPriceXaf", grid - 1000)));
        assertThat(saved.path("gapXaf").asLong()).isEqualTo(-1000);
        assertThat(saved.path("applePriceSource").asText()).isEqualTo("DECLARED");
        assertThat(saved.path("gapLabel").asText()).isEqualTo("1 000 FCFA de moins que la grille");

        JsonNode catalogue = ok(call(get("/api/v1/admin/offers"), adminToken, null));
        JsonNode row = null;
        for (JsonNode p : catalogue.path("storeProducts")) {
            if (PRO_MONTH.equals(p.path("productId").asText())) {
                row = p;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("gridPriceXaf").asLong()).isEqualTo(grid);
        assertThat(row.path("gapXaf").asLong()).isEqualTo(-1000);
        assertThat(row.path("gapLabel").asText()).isEqualTo("1 000 FCFA de moins que la grille");
        assertThat(row.path("planLabel").asText()).isEqualTo("Pro");

        JsonNode created = ok(call(put("/api/v1/admin/offers/store-products/test.pro.quarter"), adminToken,
                Map.of("planCode", "PRO", "cycleCode", "QUARTERLY")));
        assertThat(created.path("applePriceSourceLabel").asText()).isEqualTo("Prix App Store non renseigné");
        assertThat(created.path("active").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.offer_change_log WHERE actor_id = ? "
                + "AND entity = 'STORE_PRODUCT'", Integer.class, adminId)).isEqualTo(2);

        JsonNode badPlan = error(call(put("/api/v1/admin/offers/store-products/test.free"), adminToken,
                Map.of("planCode", "FREE", "cycleCode", "MONTHLY")), 422);
        assertThat(badPlan.path("details").path("planCode").asText()).isEqualTo("Choisissez un plan payant actif");
        error(call(put("/api/v1/admin/offers/store-products/bad id"), adminToken,
                Map.of("planCode", "PRO", "cycleCode", "MONTHLY")), 422);
        error(call(put("/api/v1/admin/offers/store-products/test.x"), adminToken,
                Map.of("planCode", "PRO", "cycleCode", "MONTHLY", "declaredPriceXaf", 0)), 422);
        error(call(put("/api/v1/admin/offers/store-products/test.x"), ownerToken,
                Map.of("planCode", "PRO", "cycleCode", "MONTHLY")), 403);
    }
}
