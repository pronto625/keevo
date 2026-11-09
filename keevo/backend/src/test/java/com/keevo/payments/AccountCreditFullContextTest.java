package com.keevo.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.payments.checkout.adapter.out.gateway.MyCoolPaySignature;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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

/**
 * Story 21.3 — account credit and usage packs end to end in the real application context, on the sandbox gateway
 * (never MyCoolPay): credit applied first on a plan checkout (partial, full coverage, not chosen), credit released when
 * a request ends unpaid (cancel, provider failure, stale expiry), late payment re-taking the credit or added to the
 * credit, SMS pack purchase (webhook, replay, validation), pack consumption journal and « Mon plan » figures, support
 * adjustments, concurrency and role guards. Each test cleans its rows.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Payments — account credit and usage packs, full context (Story 21.3)")
class AccountCreditFullContextTest extends FullContextIntegrationTest {

    private static final String WEBHOOK = "/api/v1/payments/webhook/mycoolpay/test-callback";
    private static final String KEY = "test-private-key";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired UsageQuotaPort quotas;
    @Autowired com.keevo.shared.infrastructure.web.RateLimitFilter rateLimit;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final List<UUID> tenants = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String adminToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        clearRateLimit();
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        tenants.add(tenantId);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ndiaye', false)", managerId, storeId);
        managerToken = TestAccess.token(jdbc, jwtTokenProvider, managerId, schema, "MANAGER", "ACTIVE", storeId,
                false);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
    }

    @AfterEach
    void cleanUp() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM public.usage_credit_movements WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_credit_movements WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_credits WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.usage_credits WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.usage_counters WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.payment_requests WHERE tenant_id = ?", tenant);
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
        }
        for (UUID user : users) {
            jdbc.update("DELETE FROM public.users WHERE id = ?", user);
        }
        catalogStore.invalidate();
        clearRateLimit();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void clearRateLimit() {
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(rateLimit, "buckets")).clear();
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

    private void grantCredit(long amount) throws Exception {
        ok(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", amount, "reason", "Geste commercial")));
    }

    private long balance() {
        List<Long> rows = jdbc.queryForList("SELECT balance_xaf FROM public.tenant_credits WHERE tenant_id = ?",
                Long.class, tenantId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    private int movements(String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_credit_movements WHERE tenant_id = ? "
                + "AND kind = ?", Integer.class, tenantId, kind);
    }

    private MvcResult planCheckout(boolean useCredit, String method, String phone) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("planCode", "PRO");
        b.put("cycleCode", "MONTHLY");
        b.put("useCredit", useCredit);
        if (method != null) {
            b.put("method", method);
        }
        if (phone != null) {
            b.put("phoneNumber", phone);
        }
        return call(post("/api/v1/payments/checkout"), ownerToken, b);
    }

    private MvcResult packCheckout(Map<String, Object> extra) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("packCode", "SMS_100");
        b.put("method", "MTN_MOMO");
        b.put("phoneNumber", "677000111");
        b.putAll(extra);
        return call(post("/api/v1/payments/checkout"), ownerToken, b);
    }

    private String providerRef(String id) {
        return jdbc.queryForObject("SELECT provider_transaction_ref FROM public.payment_requests WHERE id = ?::uuid",
                String.class, id);
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM public.payment_requests WHERE id = ?::uuid", String.class, id);
    }

    private Map<String, Object> callback(String ref, String status, long amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("application", "keevo");
        p.put("transaction_ref", ref);
        p.put("transaction_type", "PAYIN");
        p.put("transaction_amount", amount);
        p.put("transaction_currency", "XAF");
        p.put("transaction_operator", "CM_MOMO");
        p.put("transaction_status", status);
        p.put("signature", MyCoolPaySignature.sign(p, KEY));
        return p;
    }

    private String webhook(Map<String, Object> payload) throws Exception {
        MvcResult r = call(post(WEBHOOK), null, payload);
        return r.getResponse().getStatus() + " " + body(r);
    }

    private String planOfTenant() {
        return jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId);
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ?", Integer.class,
                tenantId);
    }

    private long smsPack() {
        List<Long> rows = jdbc.queryForList("SELECT remaining FROM public.usage_credits WHERE tenant_id = ? "
                + "AND metric = 'SMS'", Long.class, tenantId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    private UUID user(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", Math.abs(id.getLeastSignificantBits() % 100_000_000L));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role, first_name, last_name) VALUES (?, now(), now(), true, 0, 'hash', ?, 'MANAGER', "
                + "?, ?)", id, phone, firstName, lastName);
        users.add(id);
        return id;
    }

    // ── credit on a plan checkout ────────────────────────────────────────────

    @Test
    @DisplayName("credit 1 000 + PRO 3 500 with useCredit → PENDING, 1 000 applied, 2 500 asked; webhook → plan PRO, "
            + "ledger 2 500, history −1 000 « Utilisé pour un paiement » « Pro · Mensuel », balance 0")
    void partialCredit() throws Exception {
        grantCredit(1000);
        JsonNode p = ok(planCheckout(true, "MTN_MOMO", "677000111"));
        assertThat(p.path("status").asText()).isEqualTo("PENDING");
        assertThat(p.path("kind").asText()).isEqualTo("PLAN");
        assertThat(p.path("creditAppliedXaf").asLong()).isEqualTo(1000);
        assertThat(p.path("amountXaf").asLong()).isEqualTo(2500);
        assertThat(balance()).isZero();
        String id = p.path("id").asText();

        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(planOfTenant()).isEqualTo("PRO");
        assertThat(jdbc.queryForObject("SELECT amount_xaf FROM public.tenant_payments WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(2500);

        JsonNode credit = ok(call(get("/api/v1/payments/credit"), ownerToken, null));
        assertThat(credit.path("balanceXaf").asLong()).isZero();
        JsonNode use = credit.path("movements").get(0);
        assertThat(use.path("kind").asText()).isEqualTo("PAYMENT_USE");
        assertThat(use.path("label").asText()).isEqualTo("Utilisé pour un paiement");
        assertThat(use.path("detail").asText()).isEqualTo("Pro · Mensuel");
        assertThat(use.path("amountXaf").asLong()).isEqualTo(-1000);
        assertThat(use.path("balanceAfterXaf").asLong()).isZero();
        JsonNode grant = credit.path("movements").get(1);
        assertThat(grant.path("label").asText()).isEqualTo("Ajustement par le support");
        assertThat(grant.has("reason")).isFalse(); // the support's reason is not shown to the owner
    }

    @Test
    @DisplayName("credit 5 000 covers PRO 3 500 → CONFIRMED at once without method/phone, balance 1 500, no ledger "
            + "row; without useCredit the balance is untouched")
    void creditCoversAllAndNotChosen() throws Exception {
        grantCredit(5000);
        JsonNode p = ok(planCheckout(true, null, null));
        assertThat(p.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(p.path("amountXaf").asLong()).isZero();
        assertThat(p.path("creditAppliedXaf").asLong()).isEqualTo(3500);
        assertThat(p.path("message").asText()).startsWith("Votre plan Pro est actif jusqu'au ");
        assertThat(jdbc.queryForObject("SELECT provider FROM public.payment_requests WHERE id = ?::uuid",
                String.class, p.path("id").asText())).isEqualTo("NONE");
        assertThat(planOfTenant()).isEqualTo("PRO");
        assertThat(balance()).isEqualTo(1500);
        assertThat(ledgerRows()).isZero();

        JsonNode notChosen = ok(planCheckout(false, "MTN_MOMO", "677000111"));
        assertThat(notChosen.path("creditAppliedXaf").asLong()).isZero();
        assertThat(notChosen.path("amountXaf").asLong()).isEqualTo(3500);
        assertThat(balance()).isEqualTo(1500);
    }

    @Test
    @DisplayName("useCredit without credit and without method → 422; the credit is released once when a request "
            + "ends unpaid: cancel, provider failure, stale expiry")
    void creditReleasedWhenUnpaid() throws Exception {
        assertThat(error(planCheckout(true, null, null), 422).path("details").has("method")).isTrue();
        grantCredit(1000);

        String cancelled = ok(planCheckout(true, "MTN_MOMO", "677000111")).path("id").asText();
        assertThat(balance()).isZero();
        ok(call(post("/api/v1/payments/" + cancelled + "/cancel"), ownerToken, null));
        assertThat(balance()).isEqualTo(1000);
        assertThat(movements("PAYMENT_RELEASE")).isEqualTo(1);
        error(call(post("/api/v1/payments/" + cancelled + "/cancel"), ownerToken, null), 409);
        assertThat(balance()).isEqualTo(1000);

        String failed = ok(planCheckout(true, "MTN_MOMO", "677000111")).path("id").asText();
        assertThat(webhook(callback(providerRef(failed), "FAILED", 2500))).isEqualTo("200 OK");
        assertThat(status(failed)).isEqualTo("FAILED");
        assertThat(balance()).isEqualTo(1000);
        assertThat(movements("PAYMENT_RELEASE")).isEqualTo(2);

        String stale = ok(planCheckout(true, "MTN_MOMO", "677000111")).path("id").asText();
        jdbc.update("UPDATE public.payment_requests SET updated_at = now() - interval '11 minutes' WHERE id = ?::uuid",
                stale);
        JsonNode none = ok(call(get("/api/v1/payments/current"), ownerToken, null));
        assertThat(none.isNull() || none.isMissingNode()).isTrue();
        assertThat(status(stale)).isEqualTo("EXPIRED");
        assertThat(balance()).isEqualTo(1000);
        assertThat(movements("PAYMENT_RELEASE")).isEqualTo(3);
        assertThat(movements("PAYMENT_USE")).isEqualTo(3);
    }

    @Test
    @DisplayName("late payment of a cancelled request: credit still there → taken again and plan applied")
    void latePaymentRetakesCredit() throws Exception {
        grantCredit(1000);
        String id = ok(planCheckout(true, "MTN_MOMO", "677000111")).path("id").asText();
        ok(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null));
        assertThat(balance()).isEqualTo(1000);

        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(planOfTenant()).isEqualTo("PRO");
        assertThat(balance()).isZero();
        assertThat(movements("PAYMENT_USE")).isEqualTo(2);
    }

    @Test
    @DisplayName("late payment, credit spent meanwhile → 2 500 added to the credit, plan not applied, status kept "
            + "with a French reason; replay adds nothing")
    void latePaymentGoesToCredit() throws Exception {
        grantCredit(1000);
        String id = ok(planCheckout(true, "MTN_MOMO", "677000111")).path("id").asText();
        ok(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null));
        ok(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", -1000, "reason", "Correction")));
        assertThat(balance()).isZero();

        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(planOfTenant()).isEqualTo("FREE");
        assertThat(balance()).isEqualTo(2500);
        assertThat(ledgerRows()).isZero();
        JsonNode view = ok(call(get("/api/v1/payments/" + id), ownerToken, null));
        assertThat(view.path("message").asText())
                .isEqualTo("Paiement reçu après l'annulation : 2 500 FCFA ajoutés à votre crédit");

        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2500))).isEqualTo("200 OK");
        assertThat(balance()).isEqualTo(2500);
        assertThat(movements("PAYMENT_TO_CREDIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("two simultaneous checkouts with credit → one PENDING (credit taken once), one 409")
    void concurrentCheckoutsWithCredit() throws Exception {
        grantCredit(1000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return planCheckout(true, "MTN_MOMO", "677000111").getResponse().getStatus();
        };
        Future<Integer> a = pool.submit(attempt);
        Future<Integer> b = pool.submit(attempt);
        start.countDown();
        List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(balance()).isZero();
        assertThat(movements("PAYMENT_USE")).isEqualTo(1);
    }

    // ── SMS packs ────────────────────────────────────────────────────────────

    private long[] packSales() throws Exception {
        JsonNode s = ok(call(get("/api/v1/admin/revenue/summary"), adminToken, null));
        return new long[] {s.path("packsSold").asLong(), s.path("packsCollectedXaf").asLong()};
    }

    @Test
    @DisplayName("SMS_100 pack: listed in the offers; checkout 2 000 → webhook → SMS +100 once (replay), PURCHASE "
            + "journaled, « Mon plan » shows the pack and its history")
    void packPurchase() throws Exception {
        JsonNode offers = ok(call(get("/api/v1/subscription/offers"), ownerToken, null));
        assertThat(offers.path("usagePacks").findValuesAsText("code")).contains("SMS_100", "SMS_250");

        long[] before = packSales();
        JsonNode p = ok(packCheckout(Map.of()));
        assertThat(p.path("kind").asText()).isEqualTo("USAGE_PACK");
        assertThat(p.path("packLabel").asText()).isEqualTo("Pack 100 SMS");
        assertThat(p.path("packMetric").asText()).isEqualTo("SMS");
        assertThat(p.path("packQuantity").asInt()).isEqualTo(100);
        assertThat(p.path("amountXaf").asLong()).isEqualTo(2000);
        assertThat(p.path("planCode").isNull()).isTrue();
        String id = p.path("id").asText();

        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2000))).isEqualTo("200 OK");
        assertThat(webhook(callback(providerRef(id), "SUCCESS", 2000))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(smsPack()).isEqualTo(100);
        assertThat(ledgerRows()).isZero();
        assertThat(planOfTenant()).isEqualTo("FREE");
        JsonNode done = ok(call(get("/api/v1/payments/" + id), ownerToken, null));
        assertThat(done.path("message").asText()).isEqualTo("100 SMS ajoutés à votre compte");

        JsonNode me = ok(call(get("/api/v1/subscription/me"), ownerToken, null));
        assertThat(me.path("credits").path("SMS").asLong()).isEqualTo(100);
        JsonNode history = ok(call(get("/api/v1/subscription/me/pack-movements"), ownerToken, null));
        assertThat(history.path("items")).hasSize(1);
        JsonNode bought = history.path("items").get(0);
        assertThat(bought.path("source").asText()).isEqualTo("PURCHASE");
        assertThat(bought.path("sourceLabel").asText()).isEqualTo("Pack acheté");
        assertThat(bought.path("change").asInt()).isEqualTo(100);
        assertThat(bought.path("remainingAfter").asLong()).isEqualTo(100);
        assertThat(bought.path("byLabel").asText()).isEqualTo("Le propriétaire"); // owner without a name row
        assertThat(history.path("nextCursor").isNull()).isTrue();

        long[] after = packSales(); // this pack only: one sale, its cash
        assertThat(after[0] - before[0]).isEqualTo(1);
        assertThat(after[1] - before[1]).isEqualTo(2000);
    }

    @Test
    @DisplayName("pack checkout validation: promo code → 422, unknown pack → 422, plan and pack → 422; a pack fully "
            + "covered by the credit is credited at once")
    void packValidationAndCredit() throws Exception {
        assertThat(error(packCheckout(Map.of("promoCode", "BIENVENUE")), 422).path("error").asText())
                .isEqualTo("Les codes promo ne s'appliquent pas aux packs");
        assertThat(error(packCheckout(Map.of("packCode", "NOPE_42")), 422).path("error").asText())
                .isEqualTo("Ce pack n'est pas proposé");
        assertThat(error(packCheckout(Map.of("planCode", "PRO")), 422).path("error").asText())
                .isEqualTo("Choisissez un plan ou un pack, pas les deux");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.payment_requests WHERE tenant_id = ?",
                Integer.class, tenantId)).isZero();

        grantCredit(2000);
        long[] before = packSales();
        JsonNode covered = ok(call(post("/api/v1/payments/checkout"), ownerToken,
                Map.of("packCode", "SMS_100", "useCredit", true)));
        assertThat(covered.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(smsPack()).isEqualTo(100);
        assertThat(balance()).isZero();
        long[] after = packSales(); // sold, but paid by the credit: no cash collected
        assertThat(after[0] - before[0]).isEqualTo(1);
        assertThat(after[1] - before[1]).isZero();
        JsonNode credit = ok(call(get("/api/v1/payments/credit"), ownerToken, null));
        assertThat(credit.path("movements").get(0).path("detail").asText()).isEqualTo("Pack 100 SMS");
    }

    @Test
    @DisplayName("a pack unit consumed after the lifetime quota → journal (manager name, « Quota à vie épuisé »), "
            + "packsUsedThisMonth; a rolled-back probe leaves nothing")
    void packConsumptionJournal() throws Exception {
        UUID named = managerId; // name read from the company's employees
        tx.executeWithoutResult(s -> jdbc.update("INSERT INTO public.usage_credits (tenant_id, metric, remaining) "
                + "VALUES (?, 'SMS', 10)", tenantId));
        jdbc.update("INSERT INTO public.usage_counters (tenant_id, metric, period, period_key, used) "
                + "VALUES (?, 'SMS', 'LIFETIME', 'LIFETIME', 5)", tenantId);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(named, null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        try {
            // a probe (rolled back) leaves no movement
            tx.executeWithoutResult(s -> {
                s.setRollbackOnly();
                quotas.assertAndIncrementForSchema(schema, UsageMetric.SMS, 1);
            });
            assertThat(smsPack()).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.usage_credit_movements WHERE tenant_id = ?",
                    Integer.class, tenantId)).isZero();
            tx.executeWithoutResult(s -> quotas.assertAndIncrementForSchema(schema, UsageMetric.SMS, 1));
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(smsPack()).isEqualTo(9);

        JsonNode me = ok(call(get("/api/v1/subscription/me"), ownerToken, null));
        assertThat(me.path("packsUsedThisMonth").path("SMS").asLong()).isEqualTo(1);
        JsonNode history = ok(call(get("/api/v1/subscription/me/pack-movements?metric=sms"), ownerToken, null));
        JsonNode used = history.path("items").get(0);
        assertThat(used.path("source").asText()).isEqualTo("CONSUMPTION");
        assertThat(used.path("change").asInt()).isEqualTo(-1);
        assertThat(used.path("remainingAfter").asLong()).isEqualTo(9);
        assertThat(used.path("counterLabel").asText()).isEqualTo("Quota à vie épuisé");
        assertThat(used.path("byLabel").asText()).isEqualTo("Awa Ndiaye");
        assertThat(used.path("metricLabel").asText()).isEqualTo("SMS");

        assertThat(error(call(get("/api/v1/subscription/me/pack-movements?metric=FAX"), ownerToken, null), 422)
                .path("details").has("metric")).isTrue();
        assertThat(error(call(get("/api/v1/subscription/me/pack-movements?cursor=abc"), ownerToken, null), 422)
                .path("details").has("cursor")).isTrue();
        error(call(get("/api/v1/subscription/me/pack-movements"), managerToken, null), 403);
    }

    @Test
    @DisplayName("pack history pages with a cursor (newest first, no overlap)")
    void packHistoryPaging() throws Exception {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 3; i++) {
                jdbc.update("INSERT INTO public.usage_credit_movements (id, tenant_id, metric, delta, remaining_after, "
                        + "source, created_at) VALUES (gen_random_uuid(), ?, 'SMS', 10, ?, 'MIGRATION', "
                        + "now() - make_interval(mins => ?))", tenantId, 10L * (3 - i), i);
            }
        });
        JsonNode first = ok(call(get("/api/v1/subscription/me/pack-movements?limit=2"), ownerToken, null));
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("items").get(0).path("byLabel").isNull()).isTrue();
        assertThat(first.path("items").get(0).path("sourceLabel").asText()).isEqualTo("Repris de votre ancien compte");
        String cursor = first.path("nextCursor").asText();
        JsonNode second = ok(call(get("/api/v1/subscription/me/pack-movements?limit=2&cursor=" + cursor), ownerToken,
                null));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("items").get(0).path("remainingAfter").asLong()).isEqualTo(10);
    }

    // ── support and guards ───────────────────────────────────────────────────

    @Test
    @DisplayName("support adjustments: + then − with reasons and author, never negative (409), 422 on zero amount or "
            + "missing reason, 404 unknown company; owner/manager guards")
    void supportAdjustmentsAndGuards() throws Exception {
        UUID admin = user("Paul", "Support");
        String namedAdmin = TestAccess.token(jdbc, jwtTokenProvider, admin, "public", "SUPER_ADMIN");
        JsonNode added = ok(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), namedAdmin,
                Map.of("amountXaf", 5000, "reason", "Geste commercial")));
        assertThat(added.path("balanceXaf").asLong()).isEqualTo(5000);
        JsonNode line = added.path("movements").get(0);
        assertThat(line.path("reason").asText()).isEqualTo("Geste commercial");
        assertThat(line.path("byLabel").asText()).isEqualTo("Paul Support");
        assertThat(line.path("label").asText()).isEqualTo("Ajustement par le support");

        JsonNode tooMuch = error(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", -6000, "reason", "Correction")), 409);
        assertThat(tooMuch.path("error").asText()).isEqualTo("Le crédit ne peut pas devenir négatif (solde : 5 000 FCFA)");
        assertThat(balance()).isEqualTo(5000);
        JsonNode removed = ok(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", -2000, "reason", "Correction")));
        assertThat(removed.path("balanceXaf").asLong()).isEqualTo(3000);
        assertThat(removed.path("movements").get(0).path("byLabel").asText()).isEqualTo("Le support Keevo");

        assertThat(error(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", 0, "reason", "Rien")), 422).path("details").has("amountXaf")).isTrue();
        assertThat(error(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), adminToken,
                Map.of("amountXaf", 100)), 422).path("details").has("reason")).isTrue();
        error(call(get("/api/v1/admin/tenants/" + UUID.randomUUID() + "/credit"), adminToken, null), 404);
        assertThat(ok(call(get("/api/v1/admin/tenants/" + tenantId + "/credit?limit=1"), adminToken, null))
                .path("nextCursor").isNull()).isFalse();

        error(call(get("/api/v1/payments/credit"), managerToken, null), 403);
        error(call(get("/api/v1/payments/credit"), adminToken, null), 403);
        error(call(post("/api/v1/admin/tenants/" + tenantId + "/credit/adjustments"), ownerToken,
                Map.of("amountXaf", 100, "reason", "Moi-même")), 403);
        assertThat(balance()).isEqualTo(3000);

        // the journal is append-only
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE public.tenant_credit_movements SET amount_xaf = 1 WHERE tenant_id = ?", tenantId))
                .hasStackTraceContaining("append-only");
    }
}
