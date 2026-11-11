package com.keevo.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.13 (reprise 10/11/2026) — with the production security filter, JWTs and module gates: every stock read path
 * (REST multi-store overview / store detail / availability, product stock and history, inventory-counting reads, sync
 * pull) gives a store member (MANAGER, EMPLOYEE, VIEWER) only their own store and the OWNER every store, never another
 * tenant's data (user decision D1); purchase prices reach the OWNER and the MANAGER only — REST product, pricing,
 * threshold response and sync pull — and an EMPLOYEE update never erases the stored price (D2).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Store-member stock scope and purchase-price visibility (Story 16.13 reprise)")
class StoreMemberStockAndPurchasePriceFullContextTest extends FullContextIntegrationTest {

    static final String OTHER_STORE = "Vous ne pouvez consulter que le stock de votre boutique.";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;

    private final List<String> schemas = new ArrayList<>();
    private String schema;
    private UUID storeA;
    private UUID storeB;
    private UUID product;
    private String owner;
    private String managerA;
    private String employeeA;
    private String viewerA;
    private String otherSchema;
    private String otherOwner;

    @BeforeEach
    void setUp() {
        schema = newTenant();
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, barcode, status, price, buy_price, "
                + "transport_cost) VALUES (?, 'Riz parfumé 25 kg', 'KEV-RIZ025', '6201234567001', 'ACTIVE', 1000, 600, 50)",
                product);
        level(schema, product, storeA, 4);
        level(schema, product, storeB, 6);
        owner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        managerA = member(schema, "MANAGER", storeA);
        employeeA = member(schema, "EMPLOYEE", storeA);
        viewerA = member(schema, "VIEWER", storeA);

        otherSchema = newTenant();
        otherOwner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), otherSchema, "OWNER");
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", s);
            moduleRegistry.evict(s);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private String newTenant() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        schemas.add(s);
        UUID tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s);
        for (String module : List.of("multi-store", "counting")) { // premium modules read below
            jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, 'ACTIVE', "
                    + "'PREMIUM') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = 'ACTIVE'", tenantId, module);
        }
        moduleRegistry.evict(s);
        return s;
    }

    private String member(String tenant, String role, UUID store) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + tenant + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", user, store);
        return TestAccess.token(jdbc, jwtTokenProvider, user, tenant, role, "ACTIVE", store, false);
    }

    private void level(String tenant, UUID productId, UUID store, int quantity) {
        jdbc.update("INSERT INTO \"" + tenant + "\".stock_levels (product_id, store_id, quantity) VALUES (?, ?, ?)",
                productId, store, quantity);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private JsonNode data(String token, String path) throws Exception {
        return data(call(token, HttpMethod.GET, path, null), 200);
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return objectMapper.readTree(body).path("data");
    }

    private void forbidden(String token, String path) throws Exception {
        MvcResult r = call(token, HttpMethod.GET, path, null);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(path + " " + body).isEqualTo(403);
        assertThat(body).contains(OTHER_STORE);
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    /** Every page of a full pull: entity key → rows. */
    private Map<String, List<JsonNode>> pull(String token) throws Exception {
        Map<String, List<JsonNode>> rows = new java.util.HashMap<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode data = data(mockMvc.perform(request).andReturn(), 200);
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            data.path("entities").fields().forEachRemaining(e ->
                    e.getValue().forEach(n -> rows.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(n)));
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    // ── D1 — stock scope ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("overview: owner every store, each member role its own store, another tenant none of them")
    void overview() throws Exception {
        assertThat(values(data(owner, "/api/v1/stock/overview"), "storeId"))
                .containsExactlyInAnyOrder(storeA.toString(), storeB.toString());
        for (String member : List.of(managerA, employeeA, viewerA)) {
            JsonNode mine = data(member, "/api/v1/stock/overview");
            assertThat(values(mine, "storeId")).containsExactly(storeA.toString());
            assertThat(mine.get(0).path("totalValueXaf").asLong()).isEqualTo(4 * 1000);
        }
        assertThat(values(data(otherOwner, "/api/v1/stock/overview"), "storeId"))
                .doesNotContain(storeA.toString(), storeB.toString());
    }

    @Test
    @DisplayName("store detail: a member reads its store, another store is refused; another tenant sees no row of it")
    void storeDetail() throws Exception {
        JsonNode mine = data(employeeA, "/api/v1/stock/stores/" + storeA + "/products");
        assertThat(mine.path("content").get(0).path("quantity").asInt()).isEqualTo(4);
        assertThat(mine.path("totalElements").asLong()).isEqualTo(1);
        for (String member : List.of(managerA, employeeA, viewerA)) {
            forbidden(member, "/api/v1/stock/stores/" + storeB + "/products");
        }
        assertThat(data(owner, "/api/v1/stock/stores/" + storeB + "/products").path("content").get(0)
                .path("quantity").asInt()).isEqualTo(6);
        JsonNode cross = data(otherOwner, "/api/v1/stock/stores/" + storeA + "/products");
        assertThat(values(cross.path("content"), "productId")).doesNotContain(product.toString());
    }

    @Test
    @DisplayName("availability and product stock: a member gets its store's line only, the owner both")
    void availabilityAndProductStock() throws Exception {
        JsonNode mine = data(employeeA, "/api/v1/stock/products/" + product + "/availability").path("entries");
        assertThat(values(mine, "storeId")).containsExactly(storeA.toString());
        assertThat(values(data(owner, "/api/v1/stock/products/" + product + "/availability").path("entries"),
                "storeId")).containsExactlyInAnyOrder(storeA.toString(), storeB.toString());

        assertThat(values(data(viewerA, "/api/v1/products/" + product + "/stock"), "storeId"))
                .containsExactly(storeA.toString());
        assertThat(values(data(owner, "/api/v1/products/" + product + "/stock"), "storeId"))
                .containsExactlyInAnyOrder(storeA.toString(), storeB.toString());

        forbidden(managerA, "/api/v1/products/" + product + "/stock/history?storeId=" + storeB);
        data(managerA, "/api/v1/products/" + product + "/stock/history");
        data(owner, "/api/v1/products/" + product + "/stock/history?storeId=" + storeB);
    }

    @Test
    @DisplayName("inventory counting reads: a member never reads another store's session")
    void countingReads() throws Exception {
        UUID sessionB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".inventory_sessions (id, store_id, scope, started_by) "
                + "VALUES (?, ?, 'FULL', ?)", sessionB, storeB, UUID.randomUUID());
        for (String path : List.of("/counts", "/products", "/gap-report")) {
            forbidden(employeeA, "/api/v1/inventory/sessions/" + sessionB + path);
            data(owner, "/api/v1/inventory/sessions/" + sessionB + path);
        }
        forbidden(viewerA, "/api/v1/inventory/sessions?storeId=" + storeB);
        forbidden(viewerA, "/api/v1/inventory/sessions/active?storeId=" + storeB);
        assertThat(values(data(viewerA, "/api/v1/inventory/sessions"), "storeId")).doesNotContain(storeB.toString());
        assertThat(values(data(owner, "/api/v1/inventory/sessions"), "storeId")).contains(storeB.toString());
    }

    @Test
    @DisplayName("sync pull: stock levels of the member's store only, every store for the owner")
    void syncStockLevels() throws Exception {
        for (String member : List.of(managerA, employeeA, viewerA)) {
            assertThat(pull(member).get("stockLevels")).extracting(n -> n.path("storeId").asText())
                    .containsOnly(storeA.toString());
        }
        assertThat(pull(owner).get("stockLevels")).extracting(n -> n.path("storeId").asText())
                .contains(storeA.toString(), storeB.toString());
        assertThat(pull(otherOwner).getOrDefault("stockLevels", List.of()))
                .extracting(n -> n.path("productId").asText()).doesNotContain(product.toString());
    }

    // ── D2 — purchase prices ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REST product, pricing and threshold: purchase prices for OWNER and MANAGER only")
    void restPurchasePrices() throws Exception {
        for (String admin : List.of(owner, managerA)) {
            JsonNode p = data(admin, "/api/v1/products/" + product);
            assertThat(p.path("buyPrice").asInt()).isEqualTo(600);
            assertThat(p.path("transportCost").asInt()).isEqualTo(50);
            JsonNode pricing = data(admin, "/api/v1/products/" + product + "/pricing");
            assertThat(pricing.path("totalCost").asInt()).isEqualTo(650);
            assertThat(pricing.path("grossMarginXaf").asInt()).isEqualTo(350);
        }
        for (String member : List.of(employeeA, viewerA)) {
            JsonNode p = data(member, "/api/v1/products/" + product);
            assertThat(p.path("buyPrice").isNull()).isTrue();
            assertThat(p.path("transportCost").isNull()).isTrue();
            assertThat(p.path("price").asInt()).isEqualTo(1000);
            JsonNode list = data(member, "/api/v1/products");
            list.forEach(n -> assertThat(n.path("buyPrice").isNull()).isTrue());
            JsonNode pricing = data(member, "/api/v1/products/" + product + "/pricing");
            assertThat(pricing.path("sellingPrice").asInt()).isEqualTo(1000);
            for (String hidden : List.of("buyPrice", "transportCostValue", "totalCost", "grossMarginXaf",
                    "marginPercentage", "isLoss", "marginThreshold")) {
                assertThat(pricing.path(hidden).isNull()).as(hidden).isTrue();
            }
        }
        JsonNode threshold = data(call(employeeA, HttpMethod.PATCH, "/api/v1/products/" + product + "/threshold",
                Map.of("minimumThreshold", 3)), 200);
        assertThat(threshold.path("buyPrice").isNull()).isTrue();
        assertThat(threshold.path("minimumThreshold").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("an EMPLOYEE update sending the masked price never erases it; a MANAGER may change it")
    void writesNeverEraseThePrice() throws Exception {
        JsonNode updated = data(call(employeeA, HttpMethod.PATCH, "/api/v1/products/" + product,
                Map.of("buyPrice", 0, "transportCost", 0, "price", 1100)), 200);
        assertThat(updated.path("buyPrice").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT buy_price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT transport_cost FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(1100);

        data(call(managerA, HttpMethod.PATCH, "/api/v1/products/" + product, Map.of("buyPrice", 700)), 200);
        assertThat(jdbc.queryForObject("SELECT buy_price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(700);
    }

    @Test
    @DisplayName("sync pull: masked purchase prices (0 + purchasePriceHidden) for EMPLOYEE and VIEWER only")
    void syncPurchasePrices() throws Exception {
        for (String member : List.of(employeeA, viewerA)) {
            JsonNode p = pull(member).get("products").stream()
                    .filter(n -> n.path("id").asText().equals(product.toString())).findFirst().orElseThrow();
            assertThat(p.path("buyPrice").asInt()).isZero();
            assertThat(p.path("transportCost").asInt()).isZero();
            assertThat(p.path("purchasePriceHidden").asBoolean()).isTrue();
            assertThat(p.path("price").asInt()).isEqualTo(1000);
        }
        for (String admin : List.of(owner, managerA)) {
            JsonNode p = pull(admin).get("products").stream()
                    .filter(n -> n.path("id").asText().equals(product.toString())).findFirst().orElseThrow();
            assertThat(p.path("buyPrice").asInt()).isEqualTo(600);
            assertThat(p.path("purchasePriceHidden").asBoolean()).isFalse();
        }
    }

    // ── review 2026-11-10 — remaining read paths ──────────────────────────────────────────────────────

    @Test
    @DisplayName("barcode lookup and archive responses: purchase prices masked for EMPLOYEE and VIEWER")
    void lookupAndWriteResponsesMasked() throws Exception {
        for (String member : List.of(employeeA, viewerA)) {
            JsonNode found = data(member, "/api/v1/products/lookup?barcode=6201234567001");
            assertThat(found.path("id").asText()).isEqualTo(product.toString());
            assertThat(found.path("buyPrice").isNull()).isTrue();
            assertThat(found.path("transportCost").isNull()).isTrue();
        }
        assertThat(data(managerA, "/api/v1/products/lookup?barcode=6201234567001").path("buyPrice").asInt())
                .isEqualTo(600);
        JsonNode archived = data(call(employeeA, HttpMethod.PATCH, "/api/v1/products/" + product + "/archive", null), 200);
        assertThat(archived.path("buyPrice").isNull()).isTrue();
    }

    @Test
    @DisplayName("sync push UPDATE_PRODUCT with the masked 0 from an EMPLOYEE device never erases the price")
    void syncPushNeverErasesThePrice() throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("productId", product.toString());
        payload.put("buyPrice", 0);
        payload.put("transportCost", 0);
        payload.put("price", 1200);
        Map<String, Object> op = new java.util.LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "UPDATE_PRODUCT");
        op.put("entityId", product.toString());
        op.put("payload", payload);
        op.put("clientTimestamp", java.time.Instant.now().toString());
        JsonNode results = data(call(employeeA, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200).path("results");
        assertThat(results.get(0).path("status").asText()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT buy_price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(600);
        assertThat(jdbc.queryForObject("SELECT transport_cost FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT price FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, product)).isEqualTo(1200);
    }

    @Test
    @DisplayName("tenant isolation: another company never reads this product's stock nor its counting sessions")
    void otherTenantReadsNothing() throws Exception {
        UUID sessionA = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".inventory_sessions (id, store_id, scope, started_by) "
                + "VALUES (?, ?, 'FULL', ?)", sessionA, storeA, UUID.randomUUID());
        for (String path : List.of("/api/v1/products/" + product + "/stock",
                "/api/v1/stock/products/" + product + "/availability",
                "/api/v1/products/" + product,
                "/api/v1/inventory/sessions/" + sessionA + "/counts")) {
            MvcResult r = call(otherOwner, HttpMethod.GET, path, null);
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(r.getResponse().getStatus()).as(path + " " + body).isIn(404, 200);
            assertThat(body).as(path).doesNotContain("Riz parfumé").doesNotContain("\"quantity\":4");
        }
        assertThat(values(data(otherOwner, "/api/v1/inventory/sessions"), "id")).doesNotContain(sessionA.toString());
    }
}
