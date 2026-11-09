package com.keevo.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.payments.checkout.adapter.out.gateway.MyCoolPaySignature;
import com.keevo.payments.checkout.adapter.out.gateway.SandboxMobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
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
import java.time.LocalTime;
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

import static com.keevo.shared.domain.time.KeevoZones.WAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 21.1 — the Mobile Money checkout end to end in the real application context, on the sandbox gateway (never
 * MyCoolPay): USSD and OTP flows, the one-request-per-company lock (409, stale expiry, concurrency, cancel), provider
 * failure, free offer and promo-code cap, the signed webhook (confirmation, replay, bad secret / signature, unknown
 * reference, unconfirmed amount, failure), the status poll racing the webhook, the renewal period, the frozen price,
 * the ledger row seen by the super-admin, and the role guard. Each test cleans its rows.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Payments — Mobile Money checkout and webhook, full context (Story 21.1)")
class PaymentsFullContextTest extends FullContextIntegrationTest {

    private static final String WEBHOOK = "/api/v1/payments/webhook/mycoolpay/test-callback";
    private static final String KEY = "test-private-key";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired MobileMoneyGateway gateway;
    @Autowired com.keevo.shared.infrastructure.web.RateLimitFilter rateLimit;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<UUID> tenants = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clearRateLimit();
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        tenants.add(tenantId);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.payment_requests WHERE tenant_id = ?", tenant);
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
        }
        jdbc.update("DELETE FROM public.discounts WHERE created_by = ?", adminId);
        jdbc.update("DELETE FROM public.offer_change_log WHERE actor_id = ?", adminId);
        jdbc.update("DELETE FROM public.plan_price_history WHERE changed_by = ?", adminId);
        jdbc.update("INSERT INTO public.plan_prices (plan_code, cycle_code, amount_xaf) VALUES ('PRO', 'MONTHLY', 3500) "
                + "ON CONFLICT (plan_code, cycle_code) DO UPDATE SET amount_xaf = 3500");
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

    private static Map<String, Object> checkoutBody(String plan, String cycle, String method, String phone) {
        Map<String, Object> b = new HashMap<>();
        b.put("planCode", plan);
        b.put("cycleCode", cycle);
        b.put("method", method);
        b.put("phoneNumber", phone);
        return b;
    }

    private MvcResult checkout(String method, String phone) throws Exception {
        return call(post("/api/v1/payments/checkout"), ownerToken, checkoutBody("PRO", "MONTHLY", method, phone));
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

    private String webhook(String path, Map<String, Object> payload) throws Exception {
        MvcResult r = call(post(path), null, payload);
        return r.getResponse().getStatus() + " " + body(r);
    }

    private int payments() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_payments WHERE tenant_id = ?", Integer.class,
                tenantId);
    }

    private int selfServiceEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? "
                + "AND cause = 'SELF_SERVICE_PAYMENT'", Integer.class, tenantId);
    }

    private SandboxMobileMoneyGateway sandbox() {
        return (SandboxMobileMoneyGateway) gateway;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MTN checkout → PENDING USSD with the frozen quote; signed webhook → plan PRO, ledger row, event; "
            + "replay changes nothing; super-admin sees « Paiement en ligne »")
    void ussdCheckoutConfirmedByWebhook() throws Exception {
        JsonNode p = ok(checkout("MTN_MOMO", "677 00 01 11"));
        assertThat(p.path("status").asText()).isEqualTo("PENDING");
        assertThat(p.path("nextStep").asText()).isEqualTo("USSD");
        assertThat(p.path("ussdCode").asText()).isEqualTo(SandboxMobileMoneyGateway.USSD);
        assertThat(p.path("amountXaf").asLong()).isEqualTo(3500);
        assertThat(p.path("planLabel").asText()).isEqualTo("Pro");
        assertThat(p.path("message").asText()).contains("Composez *126#");
        String id = p.path("id").asText();
        assertThat(jdbc.queryForObject("SELECT phone FROM public.payment_requests WHERE id = ?::uuid", String.class,
                id)).isEqualTo("+237677000111");

        assertThat(webhook(WEBHOOK, callback(providerRef(id), "SUCCESS", 3500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        Map<String, Object> ledger = jdbc.queryForMap("SELECT amount_xaf, method, plan_code, cycle_code, "
                + "payment_request_id::text AS req, recorded_by FROM public.tenant_payments WHERE tenant_id = ?",
                tenantId);
        assertThat(ledger).containsEntry("amount_xaf", 3500).containsEntry("method", "MOBILE_MONEY")
                .containsEntry("plan_code", "PRO").containsEntry("cycle_code", "MONTHLY").containsEntry("req", id)
                .containsEntry("recorded_by", ownerId);
        assertThat(selfServiceEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("PRO");
        JsonNode me = ok(call(get("/api/v1/subscription/me"), ownerToken, null));
        assertThat(me.path("planCode").asText()).isEqualTo("PRO");
        LocalDate end = LocalDate.now(WAT).plusMonths(1).minusDays(1);
        assertThat(Instant.parse(me.path("expiresAt").asText()))
                .isEqualTo(end.atTime(LocalTime.of(23, 59, 59)).atZone(WAT).toInstant());

        assertThat(webhook(WEBHOOK, callback(providerRef(id), "SUCCESS", 3500))).isEqualTo("200 OK");
        assertThat(payments()).isEqualTo(1);
        assertThat(selfServiceEvents()).isEqualTo(1);

        JsonNode done = ok(call(get("/api/v1/payments/" + id), ownerToken, null));
        assertThat(done.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.path("message").asText()).startsWith("Votre plan Pro est actif jusqu'au ");
        assertThat(done.path("planExpiresAt").isNull()).isFalse();

        JsonNode list = ok(call(get("/api/v1/admin/tenants/" + tenantId + "/payments"), adminToken, null));
        assertThat(list.get(0).path("recordedByLabel").asText()).isEqualTo("Paiement en ligne (Mobile Money)");
        assertThat(list.get(0).path("online").asBoolean()).isTrue();
        assertThat(list.get(0).path("planLabel").asText()).isEqualTo("Pro");
    }

    @Test
    @DisplayName("Orange Money → OTP; wrong code → FAILED « Code de validation incorrect » (lock released); right "
            + "code → USSD step; no second code")
    void otpFlow() throws Exception {
        JsonNode first = ok(checkout("ORANGE_MONEY", "+237 690 00 00 01"));
        assertThat(first.path("nextStep").asText()).isEqualTo("OTP");
        assertThat(first.path("message").asText()).isEqualTo("Saisissez le code reçu par SMS pour valider le paiement");
        String id = first.path("id").asText();
        JsonNode refused = ok(call(post("/api/v1/payments/" + id + "/authorize"), ownerToken,
                Map.of("code", SandboxMobileMoneyGateway.WRONG_CODE)));
        assertThat(refused.path("status").asText()).isEqualTo("FAILED");
        assertThat(refused.path("message").asText()).isEqualTo("Code de validation incorrect");

        String id2 = ok(checkout("ORANGE_MONEY", "690000001")).path("id").asText();
        assertThat(error(call(post("/api/v1/payments/" + id2 + "/authorize"), ownerToken, Map.of("code", "12")), 422)
                .path("details").has("code")).isTrue();
        JsonNode accepted = ok(call(post("/api/v1/payments/" + id2 + "/authorize"), ownerToken,
                Map.of("otp", "123456")));
        assertThat(accepted.path("status").asText()).isEqualTo("PENDING");
        assertThat(accepted.path("nextStep").asText()).isEqualTo("USSD");
        assertThat(accepted.path("message").asText()).isEqualTo("Validez le paiement sur votre téléphone");
        JsonNode again = error(call(post("/api/v1/payments/" + id2 + "/authorize"), ownerToken,
                Map.of("code", "123456")), 409);
        assertThat(again.path("error").asText()).isEqualTo("Ce paiement n'attend pas de code de validation");
    }

    @Test
    @DisplayName("one payment in flight: second checkout 409, current shows it, cancel releases it, a stale PENDING "
            + "is expired by the next checkout")
    void lockCancelAndStale() throws Exception {
        String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        JsonNode busy = error(checkout("MTN_MOMO", "677000111"), 409);
        assertThat(busy.path("domainCode").asText()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(busy.path("error").asText()).isEqualTo("Un paiement est déjà en cours. Terminez-le ou annulez-le.");
        assertThat(ok(call(get("/api/v1/payments/current"), ownerToken, null)).path("id").asText()).isEqualTo(id);

        JsonNode cancelled = ok(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("statusLabel").asText()).isEqualTo("Paiement annulé");
        JsonNode none = ok(call(get("/api/v1/payments/current"), ownerToken, null));
        assertThat(none.isNull() || none.isMissingNode()).isTrue();
        assertThat(error(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null), 409)
                .path("error").asText()).isEqualTo("Ce paiement ne peut plus être annulé");

        String id2 = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        jdbc.update("UPDATE public.payment_requests SET updated_at = now() - interval '11 minutes' WHERE id = ?::uuid",
                id2);
        String id3 = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        assertThat(status(id2)).isEqualTo("EXPIRED");
        assertThat(status(id3)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("two simultaneous checkouts of one company → one PENDING, one 409")
    void concurrentCheckouts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return checkout("MTN_MOMO", "677000111").getResponse().getStatus();
        };
        Future<Integer> a = pool.submit(attempt);
        Future<Integer> b = pool.submit(attempt);
        start.countDown();
        List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.payment_requests WHERE tenant_id = ? "
                + "AND status = 'PENDING'", Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("provider failure at initiation → 502 French message, request FAILED, next checkout accepted; bad "
            + "number or method → 422")
    void providerFailureAndValidation() throws Exception {
        JsonNode down = error(checkout("MTN_MOMO", SandboxMobileMoneyGateway.FAILING_PHONE), 502);
        assertThat(down.path("domainCode").asText()).isEqualTo("PAYMENT_PROVIDER_ERROR");
        assertThat(down.path("error").asText()).isEqualTo("Le paiement n'a pas pu démarrer. Vérifiez le numéro et "
                + "réessayez.");
        assertThat(jdbc.queryForObject("SELECT status FROM public.payment_requests WHERE tenant_id = ?", String.class,
                tenantId)).isEqualTo("FAILED");
        ok(checkout("MTN_MOMO", "677000111"));

        jdbc.update("UPDATE public.payment_requests SET status = 'CANCELLED' WHERE tenant_id = ?", tenantId);
        assertThat(error(checkout("MTN_MOMO", "+33612345678"), 422).path("details").has("phoneNumber")).isTrue();
        assertThat(error(checkout("WAVE", "677000111"), 422).path("details").has("method")).isTrue();
        assertThat(error(call(post("/api/v1/payments/checkout"), ownerToken,
                checkoutBody("NOPE", "MONTHLY", "MTN_MOMO", "677000111")), 422).path("details").has("planCode"))
                .isTrue();
        assertThat(error(call(post("/api/v1/payments/checkout"), ownerToken,
                checkoutBody("PRO", "WEEKLY", "MTN_MOMO", "677000111")), 422).path("details").has("cycleCode"))
                .isTrue();
    }

    @Test
    @DisplayName("free offer (100 % promo code, cap 1) → CONFIRMED at once, no ledger row, counted; the code is then "
            + "exhausted")
    void freeOfferAndCap() throws Exception {
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMO_CODE", "label",
                "Offert", "percentOff", 100, "planCodes", List.of("PRO"), "promoCode", "OFFERT21",
                "maxRedemptions", 1)));
        Map<String, Object> b = checkoutBody("PRO", "MONTHLY", null, null);
        b.put("promoCode", "offert21");
        JsonNode free = ok(call(post("/api/v1/payments/checkout"), ownerToken, b));
        assertThat(free.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(free.path("amountXaf").asLong()).isZero();
        assertThat(free.path("discountLabel").asText()).isEqualTo("Offert");
        assertThat(payments()).isZero();
        assertThat(selfServiceEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT redemption_count FROM public.discounts WHERE promo_code = 'OFFERT21'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT promo_code FROM public.payment_requests WHERE tenant_id = ?",
                String.class, tenantId)).isEqualTo("OFFERT21");

        JsonNode offers = ok(call(get("/api/v1/subscription/offers").param("promoCode", "OFFERT21"), ownerToken,
                null));
        assertThat(offers.path("promoCode").path("valid").asBoolean()).isFalse();
        assertThat(error(call(post("/api/v1/payments/checkout"), ownerToken, b), 422).path("details")
                .has("promoCode")).isTrue();
        JsonNode discounts = ok(call(get("/api/v1/admin/offers/discounts"), adminToken, null));
        JsonNode used = null;
        for (JsonNode d : discounts) {
            if ("OFFERT21".equals(d.path("promoCode").asText())) {
                used = d;
            }
        }
        assertThat(used).isNotNull();
        assertThat(used.path("redemptionCount").asInt()).isEqualTo(1);
        assertThat(used.path("maxRedemptions").asInt()).isEqualTo(1);
        assertThat(used.path("live").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("webhook: bad secret / bad signature → KO; unknown reference → OK ignored; amount below due → KO; "
            + "signed FAILED → request FAILED")
    void webhookRejections() throws Exception {
        String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        String ref = providerRef(id);
        assertThat(webhook("/api/v1/payments/webhook/mycoolpay/wrong", callback(ref, "SUCCESS", 3500)))
                .isEqualTo("200 KO");
        Map<String, Object> tampered = callback(ref, "SUCCESS", 3500);
        tampered.put("transaction_amount", 100);
        assertThat(webhook(WEBHOOK, tampered)).isEqualTo("200 KO");
        assertThat(webhook(WEBHOOK, callback("MCP-UNKNOWN-" + UUID.randomUUID(), "SUCCESS", 3500)))
                .isEqualTo("200 OK");
        assertThat(webhook(WEBHOOK, callback(ref, "SUCCESS", 3000))).isEqualTo("200 KO");
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(payments()).isZero();

        assertThat(webhook(WEBHOOK, callback(ref, "FAILED", 3500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(ok(call(get("/api/v1/payments/" + id), ownerToken, null)).path("message").asText())
                .isEqualTo("Le paiement n'a pas abouti");
        assertThat(selfServiceEvents()).isZero();
    }

    @Test
    @DisplayName("status poll confirms when the provider says paid; poll and webhook racing → one payment, one "
            + "extension")
    void pollRacingWebhook() throws Exception {
        String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        String ref = providerRef(id);
        assertThat(ok(call(get("/api/v1/payments/" + id), ownerToken, null)).path("status").asText())
                .isEqualTo("PENDING");
        sandbox().simulate(ref, ProviderStatus.SUCCESS, 3500L, "XAF");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<String> hook = pool.submit(() -> {
            start.await();
            return webhook(WEBHOOK, callback(ref, "SUCCESS", 3500));
        });
        Future<Integer> poll = pool.submit(() -> {
            start.await();
            return call(get("/api/v1/payments/" + id), ownerToken, null).getResponse().getStatus();
        });
        start.countDown();
        assertThat(hook.get(30, TimeUnit.SECONDS)).isEqualTo("200 OK");
        assertThat(poll.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();
        assertThat(status(id)).isEqualTo("CONFIRMED");
        assertThat(payments()).isEqualTo(1);
        assertThat(selfServiceEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("renewal of a running PRO plan: quarterly period from the day after its end; the price frozen at "
            + "initiation is charged even after a price change; a cancelled request paid later is honoured")
    void renewalFrozenPriceAndLatePayment() throws Exception {
        LocalDate currentEnd = LocalDate.now(WAT).plusDays(20);
        Instant currentExpiry = currentEnd.atTime(LocalTime.of(23, 59, 59)).atZone(WAT).toInstant();
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PRO', expires_at = ?",
                Timestamp.from(currentExpiry));
        jdbc.update("UPDATE public.tenants SET plan_type = 'PRO' WHERE id = ?", tenantId);

        String id = ok(call(post("/api/v1/payments/checkout"), ownerToken,
                checkoutBody("PRO", "QUARTERLY", "MTN_MOMO", "677000111"))).path("id").asText();
        ok(call(put("/api/v1/admin/offers/prices/PRO/QUARTERLY"), adminToken, Map.of("amountXaf", 12000)));
        ok(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null));

        assertThat(webhook(WEBHOOK, callback(providerRef(id), "SUCCESS", 9500))).isEqualTo("200 OK");
        assertThat(status(id)).isEqualTo("CONFIRMED");
        Map<String, Object> ledger = jdbc.queryForMap("SELECT amount_xaf, period_start, period_end "
                + "FROM public.tenant_payments WHERE tenant_id = ?", tenantId);
        LocalDate start = currentEnd.plusDays(1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        assertThat(ledger.get("amount_xaf")).isEqualTo(9500);
        assertThat(((java.sql.Date) ledger.get("period_start")).toLocalDate()).isEqualTo(start);
        assertThat(((java.sql.Date) ledger.get("period_end")).toLocalDate()).isEqualTo(end);
        Timestamp expiry = jdbc.queryForObject("SELECT expires_at FROM \"" + schema + "\".subscriptions",
                Timestamp.class);
        assertThat(expiry.toInstant()).isEqualTo(end.atTime(LocalTime.of(23, 59, 59)).atZone(WAT).toInstant());
        jdbc.update("UPDATE public.plan_prices SET amount_xaf = 9500 WHERE plan_code = 'PRO' AND cycle_code = "
                + "'QUARTERLY'");
    }

    @Test
    @DisplayName("stale INITIATING (> 2 min) is expired by the next checkout; a PENDING of 3 min still holds the lock")
    void staleInitiating() throws Exception {
        String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        jdbc.update("UPDATE public.payment_requests SET status = 'INITIATING', updated_at = now() - interval "
                + "'3 minutes' WHERE id = ?::uuid", id);
        String id2 = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        assertThat(status(id)).isEqualTo("EXPIRED");
        jdbc.update("UPDATE public.payment_requests SET updated_at = now() - interval '3 minutes' WHERE id = ?::uuid",
                id2);
        error(checkout("MTN_MOMO", "677000111"), 409);
        assertThat(status(id2)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("paid payment with a 10 % promo code: quoted 3 150, confirmed, code use counted; the plan's modules "
            + "are granted to the company")
    void paidPromoCountedAndModulesGranted() throws Exception {
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMO_CODE", "label",
                "Dix pour cent", "percentOff", 10, "planCodes", List.of("PRO"), "promoCode", "DIX21")));
        Map<String, Object> b = checkoutBody("PRO", "MONTHLY", "MTN_MOMO", "677000111");
        b.put("promoCode", "dix21");
        JsonNode p = ok(call(post("/api/v1/payments/checkout"), ownerToken, b));
        assertThat(p.path("amountXaf").asLong()).isEqualTo(3150);
        assertThat(p.path("reductionXaf").asLong()).isEqualTo(350);
        String id = p.path("id").asText();
        assertThat(webhook(WEBHOOK, callback(providerRef(id), "SUCCESS", 3150))).isEqualTo("200 OK");
        assertThat(jdbc.queryForObject("SELECT redemption_count FROM public.discounts WHERE promo_code = 'DIX21'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount_xaf FROM public.tenant_payments WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(3150);

        JsonNode offers = ok(call(get("/api/v1/subscription/offers"), ownerToken, null));
        List<String> proModules = new ArrayList<>();
        for (JsonNode plan : offers.path("plans")) {
            if ("PRO".equals(plan.path("code").asText())) {
                plan.path("modules").forEach(m -> proModules.add(m.path("moduleId").asText()));
            }
        }
        List<String> granted = jdbc.queryForList("SELECT module_id FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND origin = 'PLAN'", String.class, tenantId);
        assertThat(granted).containsAll(proModules);
    }

    @Test
    @DisplayName("buying another plan during a running paid plan starts today (no unpaid upgrade days)")
    void otherPlanStartsToday() throws Exception {
        LocalDate currentEnd = LocalDate.now(WAT).plusDays(20);
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PRO', expires_at = ?",
                Timestamp.from(currentEnd.atTime(LocalTime.of(23, 59, 59)).atZone(WAT).toInstant()));
        jdbc.update("UPDATE public.tenants SET plan_type = 'PRO' WHERE id = ?", tenantId);
        String id = ok(call(post("/api/v1/payments/checkout"), ownerToken,
                checkoutBody("TEAM", "MONTHLY", "MTN_MOMO", "677000111"))).path("id").asText();
        assertThat(webhook(WEBHOOK, callback(providerRef(id), "SUCCESS", 5500))).isEqualTo("200 OK");
        Map<String, Object> ledger = jdbc.queryForMap("SELECT period_start, period_end FROM public.tenant_payments "
                + "WHERE tenant_id = ?", tenantId);
        LocalDate today = LocalDate.now(WAT);
        assertThat(((java.sql.Date) ledger.get("period_start")).toLocalDate()).isEqualTo(today);
        assertThat(((java.sql.Date) ledger.get("period_end")).toLocalDate())
                .isEqualTo(today.plusMonths(1).minusDays(1));
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("TEAM");
    }

    @Test
    @DisplayName("an uncapped 100 % promotion is taken once per company")
    void freeOfferOnce() throws Exception {
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMOTION", "label",
                "Lancement offert", "percentOff", 100, "planCodes", List.of("PRO"), "endsAt",
                Instant.now().plusSeconds(86_400).toString())));
        Map<String, Object> b = checkoutBody("PRO", "MONTHLY", null, null);
        assertThat(ok(call(post("/api/v1/payments/checkout"), ownerToken, b)).path("status").asText())
                .isEqualTo("CONFIRMED");
        JsonNode again = error(call(post("/api/v1/payments/checkout"), ownerToken, b), 422);
        assertThat(again.path("error").asText()).isEqualTo("Vous avez déjà profité de cette offre");
        assertThat(selfServiceEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("at most 5 checkouts per company per hour → 429 with a French message")
    void checkoutThrottle() throws Exception {
        for (int i = 0; i < 5; i++) {
            String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
            ok(call(post("/api/v1/payments/" + id + "/cancel"), ownerToken, null));
        }
        JsonNode limited = error(checkout("MTN_MOMO", "677000111"), 429);
        assertThat(limited.path("error").asText()).isEqualTo("Trop de tentatives de paiement. Réessayez dans une heure.");
    }

    @Test
    @DisplayName("provider failed at initiation (no provider ref stored) but the owner paid: the callback is routed "
            + "by our reference and the payment confirmed")
    void lateCallbackRoutedByOurReference() throws Exception {
        error(checkout("MTN_MOMO", SandboxMobileMoneyGateway.FAILING_PHONE), 502);
        Map<String, Object> row = jdbc.queryForMap("SELECT id::text AS id, app_transaction_ref FROM "
                + "public.payment_requests WHERE tenant_id = ?", tenantId);
        Map<String, Object> cb = callback("MCP-LATE-" + UUID.randomUUID(), "SUCCESS", 3500);
        cb.put("app_transaction_ref", row.get("app_transaction_ref"));
        cb.put("signature", MyCoolPaySignature.sign(cb, KEY));
        assertThat(webhook(WEBHOOK, cb)).isEqualTo("200 OK");
        assertThat(status((String) row.get("id"))).isEqualTo("CONFIRMED");
        assertThat(payments()).isEqualTo(1);
    }

    @Test
    @DisplayName("only the owner pays: a store manager → 403; another company's payment → 404")
    void roleGuard() throws Exception {
        String id = ok(checkout("MTN_MOMO", "677000111")).path("id").asText();
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ndiaye', false)", managerId, storeId);
        String manager = TestAccess.token(jdbc, jwtTokenProvider, managerId, schema, "MANAGER", "ACTIVE", storeId,
                false);
        assertThat(error(call(post("/api/v1/payments/checkout"), manager,
                checkoutBody("PRO", "MONTHLY", "MTN_MOMO", "677000111")), 403).path("error").asText())
                .isEqualTo("Seul le propriétaire peut payer l'abonnement");
        error(call(get("/api/v1/payments/current"), manager, null), 403);

        String other = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenants.add(jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, other));
        String otherOwner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), other, "OWNER");
        assertThat(error(call(get("/api/v1/payments/" + id), otherOwner, null), 404).path("domainCode").asText())
                .isEqualTo("PAYMENT_NOT_FOUND");
        error(call(post("/api/v1/payments/" + id + "/cancel"), otherOwner, null), 404);
        assertThat(status(id)).isEqualTo("PENDING");
    }
}
