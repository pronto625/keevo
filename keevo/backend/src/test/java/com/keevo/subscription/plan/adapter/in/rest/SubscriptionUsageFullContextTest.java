package com.keevo.subscription.plan.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 17.10 — the « Produits » usage of {@code GET /api/v1/subscription/me} on the real HTTP chain (production
 * security filter, a session issued by the production {@link SessionTokenIssuer}, PostgreSQL): {@code currentProducts}
 * is the number of the tenant's ACTIVE, non-archived products — the same count as the product-limit guard — and
 * {@code maxProducts} comes from the plan profile (16.8). Products are created / archived through the API.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("17.10 — « Produits » usage of /subscription/me (full context)")
class SubscriptionUsageFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;

    private String schema;
    private UUID tenantId;
    private String token;

    @BeforeEach
    void setUp() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        UUID ownerId = UUID.randomUUID();
        TestAccess.grant(jdbc, ownerId, schema, "OWNER");
        token = tx.execute(s -> issuer.issue(ownerId, tenant, "OWNER", null,
                DeviceInfo.of("dev-" + UUID.randomUUID(), "Redmi")).tokens()).accessToken();
    }

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private UUID createProduct(String name, String status) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("price", 500);
        if (status != null) request.put("status", status);
        MvcResult r = call(HttpMethod.POST, "/api/v1/products", request);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(body(r)).path("data").path("id").asText());
    }

    private JsonNode subscription() throws Exception {
        MvcResult r = call(HttpMethod.GET, "/api/v1/subscription/me", null);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return objectMapper.readTree(body(r)).path("data");
    }

    @Test
    @DisplayName("new tenant → currentProducts 0 (a number, not null)")
    void newTenant_zero() throws Exception {
        JsonNode data = subscription();
        assertThat(data.has("currentProducts")).isTrue();
        assertThat(data.get("currentProducts").isInt()).as(data.toString()).isTrue();
        assertThat(data.get("currentProducts").asInt()).isZero();
    }

    @Test
    @DisplayName("7 active + 2 drafts + 1 archived, created through the API → currentProducts 7")
    void activeProductsOnly() throws Exception {
        for (int i = 1; i <= 7; i++) createProduct("Produit actif " + i, null);
        createProduct("Brouillon 1", "DRAFT");
        createProduct("Brouillon 2", "DRAFT");
        UUID archived = createProduct("Produit archivé", null);
        MvcResult a = call(HttpMethod.PATCH, "/api/v1/products/" + archived + "/archive", null);
        assertThat(a.getResponse().getStatus()).as(body(a)).isEqualTo(200);

        JsonNode data = subscription();

        assertThat(data.get("currentProducts").asInt()).as(data.toString()).isEqualTo(7);
        // response shape unchanged (V1 2.0.1): every usage field is present
        assertThat(data.fieldNames()).toIterable().contains("planType", "status", "expiresAt", "maxStores",
                "maxProducts", "maxEmployees", "currentStores", "currentProducts", "currentEmployees");
    }

    @Test
    @DisplayName("Free plan → maxProducts is the plan profile's limit; trial → null (unlimited)")
    void maxProducts_fromPlanProfile() throws Exception {
        createProduct("Savon", null);
        assertThat(subscription().get("maxProducts").isNull()).as("trial: unlimited").isTrue();

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        Integer freeLimit = jdbc.queryForObject(
                "SELECT max_products FROM public.plan_profiles WHERE plan_type = 'FREE'", Integer.class);

        JsonNode data = subscription();

        assertThat(data.get("planType").asText()).isEqualTo("FREE");
        assertThat(data.get("maxProducts").asInt()).isEqualTo(freeLimit);
        assertThat(data.get("currentProducts").asInt()).isEqualTo(1);
    }

    // ── restoring / promoting at the limit (sync) ────────────────────────────────────────────────────────

    private void freePlan() {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
    }

    /** Copies of {@code template} as ACTIVE, non-archived products, straight in SQL (the limit is in the hundreds). */
    private void fillActive(UUID template, int n) {
        List<String> cols = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'products' AND column_name NOT IN "
                + "('id', 'name', 'sku', 'status', 'archived')", String.class, schema);
        String list = String.join(", ", cols);
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status, archived, " + list + ") "
                + "SELECT gen_random_uuid(), 'Remplissage ' || g, 'KEV-F' || lpad(g::text, 5, '0'), 'ACTIVE', false, "
                + list + " FROM \"" + schema + "\".products, generate_series(1, ?) g WHERE id = ?", n, template);
    }

    private Map<String, Object> op(String type, UUID productId) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", productId.toString());
        op.put("payload", Map.of("productId", productId.toString()));
        op.put("clientTimestamp", java.time.Instant.now().toString());
        return op;
    }

    private JsonNode push(Map<String, Object> op) throws Exception {
        MvcResult r = call(HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", "dev-17-10", "operations", List.of(op)));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return objectMapper.readTree(body(r)).path("data").path("results").get(0);
    }

    private boolean archived(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT archived FROM \"" + schema + "\".products WHERE id = ?", Boolean.class, id));
    }

    @Test
    @DisplayName("Free tenant at its limit: sync UNARCHIVE / PROMOTE → REJECTED PLAN_LIMIT_EXCEEDED with details; a freed slot → APPLIED")
    void restoreAndPromote_atLimit() throws Exception {
        UUID archivedOne = createProduct("Produit archivé", null);
        assertThat(call(HttpMethod.PATCH, "/api/v1/products/" + archivedOne + "/archive", null)
                .getResponse().getStatus()).isEqualTo(200);
        UUID draft = createProduct("Brouillon à valider", "DRAFT");
        freePlan();
        int limit = jdbc.queryForObject(
                "SELECT max_products FROM public.plan_profiles WHERE plan_type = 'FREE'", Integer.class);
        fillActive(archivedOne, limit);
        assertThat(subscription().get("currentProducts").asInt()).isEqualTo(limit);

        JsonNode unarchive = push(op("UNARCHIVE_PRODUCT", archivedOne));
        assertThat(unarchive.path("status").asText()).as(unarchive.toString()).isEqualTo("REJECTED");
        assertThat(unarchive.path("reason").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(unarchive.path("conflictData").path("entity").asText()).isEqualTo("products");
        assertThat(unarchive.path("conflictData").path("limit").asInt()).isEqualTo(limit);
        assertThat(archived(archivedOne)).isTrue();

        JsonNode promote = push(op("PROMOTE_PRODUCT", draft));
        assertThat(promote.path("status").asText()).as(promote.toString()).isEqualTo("REJECTED");
        assertThat(promote.path("reason").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".products WHERE id = ?",
                String.class, draft)).isEqualTo("DRAFT");

        UUID filler = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".products WHERE name = 'Remplissage 1'",
                UUID.class);
        assertThat(call(HttpMethod.PATCH, "/api/v1/products/" + filler + "/archive", null)
                .getResponse().getStatus()).isEqualTo(200);

        JsonNode again = push(op("UNARCHIVE_PRODUCT", archivedOne));
        assertThat(again.path("status").asText()).as(again.toString()).isEqualTo("APPLIED");
        assertThat(archived(archivedOne)).isFalse();
        assertThat(subscription().get("currentProducts").asInt()).isEqualTo(limit);
    }
}

