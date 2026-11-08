package com.keevo.admin.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.capability.entitlement.PlanSettings;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 21.10 — the configurable offer catalogue end to end in the real application context: a super-admin price change
 * is seen at once by the owner's {@code GET /api/v1/subscription/offers}, an archived plan leaves the offers and the
 * activation, a kind change of a plan in use is refused, a promo code applies case-insensitively, the trial switch
 * makes the next registration start on the free plan, and every change lands in the journal. Each test restores the
 * seeded catalogue.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Offer catalogue — super-admin CRUD and owner offers, full context (Story 21.10)")
class OfferCatalogueFullContextTest extends FullContextIntegrationTest {

    private static final PlanType TEAM = PlanType.of("TEAM");
    private static final PlanType BUSINESS_TRIAL = PlanType.of("BUSINESS_TRIAL");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired com.keevo.shared.infrastructure.web.RateLimitFilter rateLimit;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<UUID> tenants = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        schema = register();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
    }

    @AfterEach
    void restore() {
        jdbc.update("INSERT INTO public.plan_prices (plan_code, cycle_code, amount_xaf) VALUES ('PRO', 'MONTHLY', 3500) "
                + "ON CONFLICT (plan_code, cycle_code) DO UPDATE SET amount_xaf = 3500");
        jdbc.update("UPDATE public.billing_cycles SET archived_at = NULL WHERE code IN ('MONTHLY', 'QUARTERLY', "
                + "'ANNUAL')");
        jdbc.update("DELETE FROM public.billing_cycles WHERE code LIKE 'TEST\\_%'");
        jdbc.update("UPDATE public.plans SET visible = true WHERE code = 'PRO'");
        jdbc.update("DELETE FROM public.plan_price_history WHERE changed_by = ?", adminId);
        jdbc.update("DELETE FROM public.discounts WHERE created_by = ?", adminId);
        jdbc.update("DELETE FROM public.offer_change_log WHERE actor_id = ?", adminId);
        jdbc.update("UPDATE public.plans SET archived_at = NULL, kind = 'PAID' WHERE code = 'PRO'");
        catalogStore.saveSettings(new PlanSettings(PlanType.FREE, BUSINESS_TRIAL, 1, TEAM, true)); // V43 + V60
        for (UUID tenant : tenants) {
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
        }
        catalogStore.invalidate();
        // all super-admin calls share the « public » rate-limit bucket: leave it empty for the next test classes
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(rateLimit, "buckets"))
                .clear();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String register() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenants.add(jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s));
        return s;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return objectMapper.readTree(body(result)).path("data");
    }

    private JsonNode offers(String promoCode) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/subscription/offers");
        if (promoCode != null) {
            request.param("promoCode", promoCode);
        }
        return ok(call(request, ownerToken, null));
    }

    private static JsonNode plan(JsonNode offers, String code) {
        for (JsonNode p : offers.path("plans")) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        return null;
    }

    private static JsonNode price(JsonNode plan, String cycle) {
        for (JsonNode p : plan.path("prices")) {
            if (cycle.equals(p.path("cycleCode").asText())) {
                return p;
            }
        }
        return null;
    }

    private static boolean absent(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner offers: visible live free and paid plans in order, seeded grid, limits and modules, current "
            + "plan; trial plan not offered")
    void offersListing() throws Exception {
        JsonNode offers = offers(null);

        List<String> codes = new ArrayList<>();
        offers.path("plans").forEach(p -> codes.add(p.path("code").asText()));
        assertThat(codes).containsSubsequence("FREE", "PRO", "TEAM", "BUSINESS")
                .doesNotContain("BUSINESS_TRIAL", "PREMIUM", "PREMIUM_TRIAL");
        assertThat(offers.path("currentPlan").path("code").asText()).isEqualTo("BUSINESS_TRIAL");
        assertThat(offers.path("currentPlan").path("label").asText()).isEqualTo("Essai Business");
        JsonNode pro = plan(offers, "PRO");
        assertThat(pro.path("label").asText()).isEqualTo("Pro");
        assertThat(pro.path("maxStores").asInt()).isEqualTo(3);
        assertThat(pro.path("modules")).isNotEmpty();
        assertThat(price(pro, "MONTHLY").path("baseXaf").asLong()).isEqualTo(3_500);
        assertThat(price(pro, "ANNUAL").path("effectiveXaf").asLong()).isEqualTo(32_760);
        assertThat(price(pro, "ANNUAL").path("perMonthXaf").asLong()).isEqualTo(2_730);
        assertThat(price(pro, "ANNUAL").path("cycleLabel").asText()).isEqualTo("Annuel");
        assertThat(plan(offers, "FREE").path("prices")).isEmpty();
        assertThat(absent(offers.path("promoCode"))).isTrue();
    }

    @Test
    @DisplayName("price change: PUT PRO × MONTHLY 4 000 → next offers show 4 000, history and journal with the actor; "
            + "out of range → 422 details.amountXaf; free plan → 422")
    void priceChangeVisibleAtOnce() throws Exception {
        JsonNode set = ok(call(put("/api/v1/admin/offers/prices/PRO/MONTHLY"), adminToken, Map.of("amountXaf", 4000)));
        assertThat(set.path("amountXaf").asLong()).isEqualTo(4_000);

        assertThat(price(plan(offers(null), "PRO"), "MONTHLY").path("baseXaf").asLong()).isEqualTo(4_000);

        JsonNode history = ok(call(get("/api/v1/admin/offers/prices/history").param("planCode", "PRO")
                .param("cycleCode", "MONTHLY"), adminToken, null));
        assertThat(history.get(0).path("oldAmountXaf").asLong()).isEqualTo(3_500);
        assertThat(history.get(0).path("newAmountXaf").asLong()).isEqualTo(4_000);
        assertThat(history.get(0).path("changedByLabel").asText()).isEqualTo("Super admin");
        assertThat(history.get(history.size() - 1).path("changedByLabel").asText()).isEqualTo("Données de départ");

        JsonNode changes = ok(call(get("/api/v1/admin/offers/changes"), adminToken, null));
        assertThat(changes.get(0).path("summary").asText())
                .isEqualTo("Prix « Pro » · Mensuel : 3 500 FCFA → 4 000 FCFA");
        assertThat(changes.get(0).path("actorLabel").asText()).isEqualTo("Super admin");

        MvcResult tooHigh = call(put("/api/v1/admin/offers/prices/PRO/MONTHLY"), adminToken,
                Map.of("amountXaf", 100_000_001));
        assertThat(tooHigh.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(tooHigh)).contains("amountXaf").contains("100 000 000");
        MvcResult free = call(put("/api/v1/admin/offers/prices/FREE/MONTHLY"), adminToken, Map.of("amountXaf", 1000));
        assertThat(free.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(free)).contains("Seuls les plans payants ont un prix");
    }

    @Test
    @DisplayName("archive PRO: companies keep it, PRO leaves the offers and the activation (422), quote refused; "
            + "archive FREE (settings) → 409")
    void archivedPlan() throws Exception {
        jdbc.update("UPDATE public.tenants SET plan_type = 'PRO' WHERE id = ?", tenantId);

        JsonNode archived = ok(call(post("/api/v1/admin/offers/plans/PRO/archive"), adminToken, null));
        assertThat(archived.path("archived").asBoolean()).isTrue();
        assertThat(archived.path("tenantCount").asLong()).isPositive();

        assertThat(plan(offers(null), "PRO")).isNull();
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("PRO");
        MvcResult activation = call(post("/api/v1/admin/tenants/{id}/plan/premium", tenantId), adminToken,
                Map.of("expiresAt", LocalDate.now().plusDays(30).toString(), "amountXaf", 3500, "method", "CASH",
                        "planCode", "PRO"));
        assertThat(activation.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(activation)).contains("Ce plan ne peut pas être attribué");
        MvcResult quote = call(get("/api/v1/admin/offers/quote").param("planCode", "PRO")
                .param("cycleCode", "MONTHLY"), adminToken, null);
        assertThat(quote.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(quote)).contains("Cette offre n'est pas proposée");

        MvcResult free = call(post("/api/v1/admin/offers/plans/FREE/archive"), adminToken, null);
        assertThat(free.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(free)).contains("changez d'abord les réglages");

        ok(call(post("/api/v1/admin/offers/plans/PRO/unarchive"), adminToken, null));
        assertThat(plan(offers(null), "PRO")).isNotNull();
    }

    @Test
    @DisplayName("kind change of a plan a company is on → 409, nothing saved")
    void kindChangeOfUsedPlan() throws Exception {
        jdbc.update("UPDATE public.tenants SET plan_type = 'PRO' WHERE id = ?", tenantId);

        MvcResult refused = call(put("/api/v1/admin/offers/plans/PRO"), adminToken, Map.of("kind", "FREE"));

        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
        assertThat(body(refused)).contains("sa nature ne peut plus changer").contains("INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT kind FROM public.plans WHERE code = 'PRO'", String.class))
                .isEqualTo("PAID");
    }

    @Test
    @DisplayName("promo code (case-insensitive) applies when best; unknown → valid=false with a French message; "
            + "duplicate live code → 409; quote with the code")
    void promoCode() throws Exception {
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMO_CODE", "label",
                "Bienvenue Bref", "percentOff", 10, "planCodes", List.of("PRO"), "promoCode", "BREF10")));

        JsonNode withCode = offers("bref10");
        assertThat(withCode.path("promoCode").path("valid").asBoolean()).isTrue();
        JsonNode monthly = price(plan(withCode, "PRO"), "MONTHLY");
        assertThat(monthly.path("effectiveXaf").asLong()).isEqualTo(3_150);
        assertThat(monthly.path("discount").path("label").asText()).isEqualTo("Bienvenue Bref");
        assertThat(absent(price(plan(withCode, "TEAM"), "MONTHLY").path("discount"))).isTrue();

        JsonNode unknown = offers("NOPE99");
        assertThat(unknown.path("promoCode").path("valid").asBoolean()).isFalse();
        assertThat(unknown.path("promoCode").path("message").asText()).isEqualTo("Code promo invalide ou expiré");
        assertThat(price(plan(unknown, "PRO"), "MONTHLY").path("effectiveXaf").asLong()).isEqualTo(3_500);

        MvcResult duplicate = call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMO_CODE",
                "label", "Doublon", "amountOffXaf", 500, "promoCode", "Bref10"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);

        JsonNode quote = ok(call(get("/api/v1/admin/offers/quote").param("planCode", "pro")
                .param("cycleCode", "monthly").param("tenantId", tenantId.toString()).param("promoCode", "bref10"),
                adminToken, null));
        assertThat(quote.path("effectiveXaf").asLong()).isEqualTo(3_150);
        MvcResult badCode = call(get("/api/v1/admin/offers/quote").param("planCode", "PRO")
                .param("cycleCode", "MONTHLY").param("promoCode", "NOPE99"), adminToken, null);
        assertThat(badCode.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(badCode)).contains("Code promo invalide ou expiré");
    }

    @Test
    @DisplayName("company discount beats a smaller promotion: best single discount, no stacking")
    void tenantDiscountBeatsPromotion() throws Exception {
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMOTION", "label",
                "Rentrée", "percentOff", 10, "endsAt", Instant.now().plus(10, ChronoUnit.DAYS).toString())));
        ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "TENANT", "label",
                "Fidélité", "amountOffXaf", 1000, "tenantId", tenantId.toString())));

        JsonNode monthly = price(plan(offers(null), "PRO"), "MONTHLY");
        assertThat(monthly.path("effectiveXaf").asLong()).isEqualTo(2_500);
        assertThat(monthly.path("discount").path("scope").asText()).isEqualTo("TENANT");
        JsonNode annual = price(plan(offers(null), "PRO"), "ANNUAL");
        assertThat(annual.path("effectiveXaf").asLong()).isEqualTo(29_484);
        assertThat(annual.path("discount").path("scope").asText()).isEqualTo("PROMOTION");
        assertThat(annual.path("discount").path("endsAt").isTextual()).isTrue();

        JsonNode discounts = ok(call(get("/api/v1/admin/offers/discounts"), adminToken, null));
        assertThat(discounts.toString()).contains("Fidélité").contains("\"live\":true");
    }

    @Test
    @DisplayName("trial off: the next registration starts on the free plan with no end date, REGISTRATION event to "
            + "the free plan; settings journaled")
    void trialOff() throws Exception {
        JsonNode settings = ok(call(put("/api/v1/admin/offers/settings"), adminToken, Map.of("trialEnabled", false)));
        assertThat(settings.path("trialEnabled").asBoolean()).isFalse();

        String s = register();
        UUID id = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s);
        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"" + s + "\".subscriptions", String.class))
                .isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM \"" + s + "\".subscriptions", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, id))
                .isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT to_plan FROM public.tenant_plan_events WHERE tenant_id = ? "
                + "AND cause = 'REGISTRATION'", String.class, id)).isEqualTo("FREE");
        JsonNode changes = ok(call(get("/api/v1/admin/offers/changes"), adminToken, null));
        assertThat(changes.get(0).path("summary").asText()).contains("essai désactivé");

        // back on: trial again
        ok(call(put("/api/v1/admin/offers/settings"), adminToken, Map.of("trialEnabled", true)));
        String again = register();
        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"" + again + "\".subscriptions", String.class))
                .isEqualTo("BUSINESS_TRIAL");
    }

    @Test
    @DisplayName("cycles: create / update / duplicate 409; an archived cycle leaves the offers prices and the quote "
            + "answers 422; unarchived it comes back")
    void cycles() throws Exception {
        JsonNode created = ok(call(post("/api/v1/admin/offers/cycles"), adminToken,
                Map.of("code", "TEST_SEMESTER", "label", "Semestriel", "months", 6)));
        assertThat(created.path("months").asInt()).isEqualTo(6);
        assertThat(call(post("/api/v1/admin/offers/cycles"), adminToken,
                Map.of("code", "TEST_SEMESTER", "label", "Doublon", "months", 6)).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(ok(call(put("/api/v1/admin/offers/cycles/TEST_SEMESTER"), adminToken,
                Map.of("label", "Six mois"))).path("label").asText()).isEqualTo("Six mois");
        MvcResult inUse = call(put("/api/v1/admin/offers/cycles/MONTHLY"), adminToken, Map.of("months", 2));
        assertThat(inUse.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(inUse)).contains("Cette durée est utilisée");

        ok(call(post("/api/v1/admin/offers/cycles/QUARTERLY/archive"), adminToken, null));
        assertThat(price(plan(offers(null), "PRO"), "QUARTERLY")).isNull();
        MvcResult quote = call(get("/api/v1/admin/offers/quote").param("planCode", "PRO")
                .param("cycleCode", "QUARTERLY"), adminToken, null);
        assertThat(quote.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(quote)).contains("Cette durée n'est pas proposée");

        ok(call(post("/api/v1/admin/offers/cycles/QUARTERLY/unarchive"), adminToken, null));
        assertThat(price(plan(offers(null), "PRO"), "QUARTERLY")).isNotNull();
    }

    @Test
    @DisplayName("hidden live paid plan: absent from the offers, quote 422, still attributable by the super-admin")
    void hiddenPlan() throws Exception {
        JsonNode hidden = ok(call(put("/api/v1/admin/offers/plans/PRO"), adminToken, Map.of("visible", false)));
        assertThat(hidden.path("visible").asBoolean()).isFalse();

        assertThat(plan(offers(null), "PRO")).isNull();
        MvcResult quote = call(get("/api/v1/admin/offers/quote").param("planCode", "PRO")
                .param("cycleCode", "MONTHLY"), adminToken, null);
        assertThat(quote.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(quote)).contains("Cette offre n'est pas proposée");
        MvcResult activation = call(post("/api/v1/admin/tenants/{id}/plan/premium", tenantId), adminToken,
                Map.of("expiresAt", LocalDate.now().plusDays(30).toString(), "amountXaf", 3500, "method", "CASH",
                        "planCode", "PRO", "cycleCode", "MONTHLY"));
        assertThat(activation.getResponse().getStatus()).as(body(activation)).isEqualTo(200);
        JsonNode payments = ok(call(get("/api/v1/admin/tenants/{id}/payments", tenantId), adminToken, null));
        assertThat(payments.get(0).path("planLabel").asText()).isEqualTo("Pro");
        assertThat(payments.get(0).path("cycleLabel").asText()).isEqualTo("Mensuel");
    }

    @Test
    @DisplayName("delete a price: 404 when absent; gone from the offers; history row with new amount null")
    void deletePrice() throws Exception {
        assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/admin/offers/prices/PRO/MONTHLY"), adminToken, null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/admin/offers/prices/PRO/MONTHLY"), adminToken, null).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(price(plan(offers(null), "PRO"), "MONTHLY")).isNull();
        JsonNode history = ok(call(get("/api/v1/admin/offers/prices/history").param("planCode", "PRO")
                .param("cycleCode", "MONTHLY"), adminToken, null));
        assertThat(history.get(0).path("oldAmountXaf").asLong()).isEqualTo(3_500);
        assertThat(absent(history.get(0).path("newAmountXaf"))).isTrue();
    }

    @Test
    @DisplayName("update then archive a discount: the new value applies at once, the archived one stops at the next "
            + "call; updating an archived discount → 409")
    void updateAndArchiveDiscount() throws Exception {
        String endsAt = Instant.now().plus(10, ChronoUnit.DAYS).toString();
        JsonNode created = ok(call(post("/api/v1/admin/offers/discounts"), adminToken, Map.of("scope", "PROMOTION",
                "label", "Rentrée", "percentOff", 10, "planCodes", List.of("PRO"), "endsAt", endsAt)));
        String id = created.path("id").asText();
        assertThat(price(plan(offers(null), "PRO"), "MONTHLY").path("effectiveXaf").asLong()).isEqualTo(3_150);

        ok(call(put("/api/v1/admin/offers/discounts/" + id), adminToken, Map.of("scope", "PROMOTION",
                "label", "Rentrée", "percentOff", 20, "planCodes", List.of("PRO"), "endsAt", endsAt)));
        assertThat(price(plan(offers(null), "PRO"), "MONTHLY").path("effectiveXaf").asLong()).isEqualTo(2_800);

        JsonNode archived = ok(call(post("/api/v1/admin/offers/discounts/" + id + "/archive"), adminToken, null));
        assertThat(archived.path("archived").asBoolean()).isTrue();
        assertThat(price(plan(offers(null), "PRO"), "MONTHLY").path("effectiveXaf").asLong()).isEqualTo(3_500);
        MvcResult refused = call(put("/api/v1/admin/offers/discounts/" + id), adminToken, Map.of("scope",
                "PROMOTION", "label", "Rentrée", "percentOff", 30, "endsAt", endsAt));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("kind change of an unused paid plan with prices → 409; plan create through a duplicate code → 409")
    void kindChangeWithPricesAndDuplicateCreate() throws Exception {
        // no company on BUSINESS? make sure of it for this check
        Long onBusiness = jdbc.queryForObject("SELECT count(*) FROM public.tenants WHERE plan_type = 'BUSINESS' "
                + "AND status <> 'DELETED'", Long.class);
        if (onBusiness == 0) {
            MvcResult refused = call(put("/api/v1/admin/offers/plans/BUSINESS"), adminToken, Map.of("kind", "FREE"));
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused)).contains("Ce plan a des prix");
        }
        MvcResult duplicate = call(post("/api/v1/admin/offers/plans"), adminToken,
                Map.of("code", "TEAM", "label", "Team bis", "kind", "PAID"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT label FROM public.plans WHERE code = 'TEAM'", String.class))
                .isEqualTo("Team");
    }

    @Test
    @DisplayName("roles: the super-admin cannot read company offers (403); an owner cannot reach the admin catalogue")
    void roles() throws Exception {
        assertThat(call(get("/api/v1/subscription/offers"), adminToken, null).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(call(get("/api/v1/admin/offers"), ownerToken, null).getResponse().getStatus()).isEqualTo(403);
        JsonNode catalogue = ok(call(get("/api/v1/admin/offers"), adminToken, null));
        assertThat(catalogue.path("cycles")).hasSize(3);
        assertThat(catalogue.path("settings").path("trialPlanLabel").asText()).isEqualTo("Essai Business");
    }
}
