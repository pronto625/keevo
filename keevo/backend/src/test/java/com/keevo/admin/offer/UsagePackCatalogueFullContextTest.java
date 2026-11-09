package com.keevo.admin.offer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 21.3 — the usage packs of the offer catalogue edited by the super-admin (ADR-05): seeded SMS packs, create and
 * update with validation, journal, the owner listing and the checkout price follow the edit, a payment already started
 * keeps its frozen price, an inactive pack is no longer sold. Seeds restored after each test.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Admin — usage pack catalogue, full context (Story 21.3)")
class UsagePackCatalogueFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.keevo.shared.infrastructure.web.RateLimitFilter rateLimit;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private UUID tenantId;
    private String ownerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        clearRateLimit();
        String schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM public.payment_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        jdbc.update("DELETE FROM public.usage_packs WHERE code = 'TEST_EMAILS_50'");
        jdbc.update("UPDATE public.usage_packs SET price_xaf = 2000, active = true, label = 'Pack 100 SMS', "
                + "quantity = 100, metric = 'SMS', sort_order = 10 WHERE code = 'SMS_100'");
        jdbc.update("DELETE FROM public.offer_change_log WHERE actor_id = ?", adminId);
        clearRateLimit();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void clearRateLimit() {
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(rateLimit, "buckets")).clear();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode error(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return objectMapper.readTree(body);
    }

    private JsonNode pack(JsonNode list, String code) {
        for (JsonNode p : list) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        return null;
    }

    private MvcResult buy(String code) throws Exception {
        return call(post("/api/v1/payments/checkout"), ownerToken,
                Map.of("packCode", code, "method", "MTN_MOMO", "phoneNumber", "677000111"));
    }

    @Test
    @DisplayName("seeded SMS packs listed (unit price), in the catalogue view; price edit journaled, next checkout "
            + "charges it, a started payment keeps its price; inactive → no longer offered nor sold")
    void editPriceAndActive() throws Exception {
        JsonNode list = ok(call(get("/api/v1/admin/offers/usage-packs"), adminToken, null));
        JsonNode sms100 = pack(list, "SMS_100");
        assertThat(sms100.path("priceXaf").asLong()).isEqualTo(2000);
        assertThat(sms100.path("unitPriceXaf").asLong()).isEqualTo(20);
        assertThat(sms100.path("metricLabel").asText()).isEqualTo("SMS");
        assertThat(pack(list, "SMS_1000").path("label").asText()).isEqualTo("Pack 1 000 SMS");
        assertThat(pack(ok(call(get("/api/v1/admin/offers"), adminToken, null)).path("usagePacks"), "SMS_250"))
                .isNotNull();

        String started = ok(buy("SMS_100")).path("id").asText();
        JsonNode edited = ok(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken,
                Map.of("priceXaf", 1800)));
        assertThat(edited.path("priceXaf").asLong()).isEqualTo(1800);
        assertThat(edited.path("quantity").asInt()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT amount_due_xaf FROM public.payment_requests WHERE id = ?::uuid",
                Long.class, started)).isEqualTo(2000);
        JsonNode journal = ok(call(get("/api/v1/admin/offers/changes"), adminToken, null));
        assertThat(journal.get(0).path("summary").asText())
                .isEqualTo("Pack « Pack 100 SMS » : 100 SMS, 1 800 FCFA (modifié ; avant : Pack « Pack 100 SMS » : "
                        + "100 SMS, 2 000 FCFA)");

        ok(call(post("/api/v1/payments/" + started + "/cancel"), ownerToken, null));
        assertThat(ok(buy("SMS_100")).path("amountXaf").asLong()).isEqualTo(1800);
        JsonNode offers = ok(call(get("/api/v1/subscription/offers"), ownerToken, null));
        assertThat(pack(offers.path("usagePacks"), "SMS_100").path("priceXaf").asLong()).isEqualTo(1800);

        ok(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken, Map.of("active", false)));
        offers = ok(call(get("/api/v1/subscription/offers"), ownerToken, null));
        assertThat(pack(offers.path("usagePacks"), "SMS_100")).isNull();
        jdbc.update("UPDATE public.payment_requests SET status = 'CANCELLED' WHERE tenant_id = ? "
                + "AND status IN ('INITIATING', 'PENDING')", tenantId);
        assertThat(error(buy("SMS_100"), 422).path("error").asText()).isEqualTo("Ce pack n'est pas proposé");
        // unchanged save: no journal line
        int lines = jdbc.queryForObject("SELECT count(*) FROM public.offer_change_log WHERE actor_id = ?",
                Integer.class, adminId);
        ok(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken, Map.of("active", false)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.offer_change_log WHERE actor_id = ?",
                Integer.class, adminId)).isEqualTo(lines);
    }

    @Test
    @DisplayName("create a pack (another metric); validation per field (422 French); super-admin only")
    void createAndValidate() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("label", "Pack 50 emails");
        body.put("metric", "emails");
        body.put("quantity", 50);
        body.put("priceXaf", 500);
        body.put("sortOrder", 90);
        JsonNode created = ok(call(put("/api/v1/admin/offers/usage-packs/test_emails_50"), adminToken, body));
        assertThat(created.path("code").asText()).isEqualTo("TEST_EMAILS_50");
        assertThat(created.path("metric").asText()).isEqualTo("EMAILS");
        assertThat(created.path("metricLabel").asText()).isEqualTo("Emails");
        assertThat(created.path("active").asBoolean()).isTrue();
        // a company whose plan does not limit emails cannot buy it (the units would never be used)
        jdbc.update("UPDATE public.tenants SET plan_type = 'PRO' WHERE id = ?", tenantId);
        assertThat(error(buy("TEST_EMAILS_50"), 422).path("error").asText())
                .isEqualTo("Votre offre ne limite pas cette consommation : ce pack ne vous servirait pas");
        // a pack keeps its content
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/TEST_EMAILS_50"), adminToken,
                Map.of("metric", "SMS")), 422).path("details").has("metric")).isTrue();

        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/NEW_PACK"), adminToken,
                Map.of("label", "Incomplet")), 422).path("details").has("label")).isTrue();
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/1BAD"), adminToken, body), 422)
                .path("details").has("code")).isTrue();
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken,
                Map.of("quantity", 0)), 422).path("details").has("quantity")).isTrue();
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken,
                Map.of("priceXaf", 0)), 422).path("error").asText())
                .isEqualTo("Le montant doit être compris entre 1 et 100 000 000 FCFA");
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken,
                Map.of("metric", "FAX")), 422).path("details").has("metric")).isTrue();
        assertThat(error(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), adminToken,
                Map.of("sortOrder", -1)), 422).path("details").has("sortOrder")).isTrue();
        error(call(put("/api/v1/admin/offers/usage-packs/SMS_100"), ownerToken, Map.of("priceXaf", 1)), 403);
        error(call(get("/api/v1/admin/offers/usage-packs"), ownerToken, null), 403);
    }
}
