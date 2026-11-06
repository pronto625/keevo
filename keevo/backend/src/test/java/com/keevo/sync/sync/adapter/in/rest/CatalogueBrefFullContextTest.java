package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.inventory.stock.application.usecase.ChangeStockTrackingUseCase;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.1 — extended catalogue on the real chain (production security filter and session, real use cases,
 * PostgreSQL): barcode and key on creation, lookup, merge (every refusal), redirection of offline operations and of
 * a re-creation by name, unarchive refused, plus the 17.12 non-regression rows (untracked sale, enabling the tracking
 * with a quantity / with nothing).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("20.1 — catalogue étendu : code-barres, clé, fusion (full context)")
class CatalogueBrefFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;

    private String schema;
    private String token;
    private UUID storeId;
    private final String deviceId = UUID.randomUUID().toString();
    private Instant clock = Instant.now().minusSeconds(600);

    @BeforeEach
    void setUp() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID ownerId = UUID.randomUUID();
        TestAccess.grant(jdbc, ownerId, schema, "OWNER");
        token = tx.execute(s -> issuer.issue(ownerId, tenant, "OWNER", null,
                DeviceInfo.of(deviceId, "Redmi")).tokens()).accessToken();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Object payload) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r));
    }

    private Map<String, Object> op(String type, String entityId, Map<String, Object> payload) {
        clock = clock.plusSeconds(1);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId);
        op.put("payload", payload);
        op.put("clientTimestamp", clock.toString());
        return op;
    }

    private Map<String, Object> createProductOp(UUID id, String name, Boolean trackStock, String barcode) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id.toString());
        p.put("name", name);
        p.put("price", 1000);
        if (trackStock != null) p.put("trackStock", trackStock);
        if (barcode != null) p.put("barcode", barcode);
        return op("CREATE_PRODUCT", id.toString(), p);
    }

    private Map<String, Object> stockEntryOp(UUID productId, int quantity) {
        return op("RECORD_STOCK_ENTRY", UUID.randomUUID().toString(), new LinkedHashMap<>(Map.of(
                "productId", productId.toString(), "storeId", storeId.toString(), "quantity", quantity)));
    }

    private static Map<String, Object> item(UUID productId, int quantity) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("itemId", UUID.randomUUID().toString());
        i.put("productId", productId.toString());
        i.put("productName", "Article");
        i.put("catalogueUnitPrice", 1000);
        i.put("appliedUnitPrice", 1000);
        i.put("quantity", quantity);
        return i;
    }

    @SafeVarargs
    private Map<String, Object> saleOp(UUID saleId, Map<String, Object>... items) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("saleId", saleId.toString());
        p.put("storeId", storeId.toString());
        p.put("paymentMode", "CASH");
        p.put("items", List.of(items));
        return op("CREATE_SALE", saleId.toString(), p);
    }

    @SafeVarargs
    private MvcResult restSale(Map<String, Object>... items) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("saleId", UUID.randomUUID().toString());
        s.put("paymentMode", "CASH");
        s.put("storeId", storeId.toString());
        s.put("items", List.of(items));
        return call(MockMvcRequestBuilders.post("/api/v1/sales"), s);
    }

    @SafeVarargs
    private final List<JsonNode> push(Map<String, Object>... ops) throws Exception {
        MvcResult r = call(MockMvcRequestBuilders.post("/api/v1/sync/push"),
                Map.of("deviceId", deviceId, "operations", List.of(ops)));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : json(r).path("data").path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        List<JsonNode> ordered = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            JsonNode result = byId.get((String) op.get("operationId"));
            assertThat(result).as("no result for %s in %s", op.get("operationType"), body(r)).isNotNull();
            ordered.add(result);
        }
        return ordered;
    }

    private static void assertStatus(JsonNode result, String status) {
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo(status);
    }

    private UUID product(String name, Boolean trackStock, String barcode) throws Exception {
        UUID id = UUID.randomUUID();
        assertStatus(push(createProductOp(id, name, trackStock, barcode)).get(0), "APPLIED");
        return id;
    }

    private Map<String, Object> row(UUID productId) {
        return jdbc.queryForMap("SELECT archived, merged_into_id, barcode, product_key, price_manual FROM \""
                + schema + "\".products WHERE id = ?", productId);
    }

    private int movementCount(UUID productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".stock_movements WHERE product_id = ?",
                Integer.class, productId);
    }

    private int levelRows(UUID productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".stock_levels WHERE product_id = ?",
                Integer.class, productId);
    }

    private Integer level(UUID productId) {
        List<Integer> q = jdbc.queryForList("SELECT quantity FROM \"" + schema + "\".stock_levels "
                + "WHERE product_id = ? AND store_id = ? AND variant_id IS NULL", Integer.class, productId, storeId);
        return q.isEmpty() ? null : q.get(0);
    }

    private MvcResult merge(UUID source, UUID target) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/v1/products/" + source + "/merge"),
                Map.of("targetId", target.toString()));
    }

    private MvcResult lookup(String code) throws Exception {
        return call(MockMvcRequestBuilders.get("/api/v1/products/lookup").param("barcode", code), null);
    }

    private JsonNode pulledProduct(UUID productId) throws Exception {
        String cursor = null;
        for (int pages = 0; pages < 50; pages++) {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull");
            if (cursor != null) request.param("cursor", cursor);
            JsonNode data = json(call(request, null)).path("data");
            for (JsonNode p : data.path("entities").path("products")) {
                if (p.path("id").asText().equals(productId.toString())) return p;
            }
            if (!data.path("hasMore").asBoolean()) return null;
            cursor = data.path("nextCursor").asText();
        }
        return null;
    }

    // ── I/O matrix ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("création REST : clé du nom / du code-barres, prix manuel ; référence libre ; recherche par code")
    void creation_keys_and_lookup() throws Exception {
        MvcResult byName = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "CRÈME Brûlée", "price", 1500));
        assertThat(byName.getResponse().getStatus()).as(body(byName)).isEqualTo(201);
        JsonNode created = json(byName).path("data");
        assertThat(created.path("productKey").asText()).isEqualTo("nm:creme brulee");
        assertThat(created.path("priceManual").asBoolean()).isTrue();
        assertThat(created.path("mergedIntoId").isNull()).isTrue();

        MvcResult byBarcode = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "Savon Lux", "price", 500, "barcode", "6111234567890"));
        JsonNode savon = json(byBarcode).path("data");
        assertThat(savon.path("productKey").asText()).isEqualTo("bc:6111234567890");

        MvcResult ref = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "Chemise Bleue", "price", 500, "barcode", "REF-001"));
        assertThat(json(ref).path("data").path("productKey").asText()).isEqualTo("nm:chemise bleue");

        MvcResult tooLong = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "Trop long", "price", 500, "barcode", "1".repeat(101)));
        assertThat(tooLong.getResponse().getStatus()).as(body(tooLong)).isEqualTo(422);

        MvcResult found = lookup("6111234567890");
        assertThat(found.getResponse().getStatus()).as(body(found)).isEqualTo(200);
        assertThat(json(found).path("data").path("id").asText()).isEqualTo(savon.path("id").asText());
        assertThat(json(lookup("REF-001")).path("data").path("name").asText()).isEqualTo("Chemise Bleue");

        MvcResult none = lookup("0000000000000");
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(none).path("domainCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(json(none).path("error").asText()).isEqualTo("Aucun produit avec ce code");
        assertThat(lookup(" ").getResponse().getStatus()).isEqualTo(422);

        // V1 body: a PATCH without barcode keeps it
        UUID savonId = UUID.fromString(savon.path("id").asText());
        MvcResult patched = call(MockMvcRequestBuilders.patch("/api/v1/products/" + savonId), Map.of("price", 650));
        assertThat(patched.getResponse().getStatus()).as(body(patched)).isEqualTo(200);
        assertThat(row(savonId).get("barcode")).isEqualTo("6111234567890");
        assertThat(row(savonId).get("product_key")).isEqualTo("bc:6111234567890");
    }

    @Test
    @DisplayName("fusion A → B : A archivé et redirigé, 200 B, audit « Produit fusionné », recherche et pull suivent")
    void merge_ok_lookup_and_pull() throws Exception {
        UUID a = product("Savon Lux", null, "6111234567890");
        UUID b = product("Savon LUX 200g", null, null);

        MvcResult merged = merge(a, b);
        assertThat(merged.getResponse().getStatus()).as(body(merged)).isEqualTo(200);
        assertThat(json(merged).path("data").path("id").asText()).isEqualTo(b.toString());
        // the target had no barcode: it inherits the source's (key recomputed)
        assertThat(json(merged).path("data").path("barcode").asText()).isEqualTo("6111234567890");
        assertThat(row(b).get("product_key")).isEqualTo("bc:6111234567890");

        assertThat(row(a).get("archived")).isEqualTo(true);
        assertThat(row(a).get("merged_into_id")).isEqualTo(b);
        Map<String, Object> audit = jdbc.queryForMap("SELECT action, value_after FROM \"" + schema
                + "\".audit_log WHERE entity_id = ? AND action = 'PRODUCT_MERGED'", a);
        assertThat((String) audit.get("value_after")).contains("Savon Lux").contains("Savon LUX 200g")
                .doesNotContain(b.toString());

        assertThat(json(lookup("6111234567890")).path("data").path("id").asText()).isEqualTo(b.toString());
        assertThat(pulledProduct(a).path("mergedIntoId").asText()).isEqualTo(b.toString());
        assertThat(pulledProduct(b).path("mergedIntoId").isNull()).isTrue();
    }

    @Test
    @DisplayName("chaîne : C fusionné dans A puis A → B ⇒ C et A pointent vers B")
    void merge_chain_stays_flat() throws Exception {
        UUID a = product("Riz parfumé", null, null);
        UUID b = product("Riz parfumé 5kg", null, null);
        UUID c = product("Riz parfume", null, null);

        assertThat(merge(c, a).getResponse().getStatus()).isEqualTo(200);
        assertThat(merge(a, b).getResponse().getStatus()).isEqualTo(200);

        assertThat(row(c).get("merged_into_id")).isEqualTo(b);
        assertThat(row(a).get("merged_into_id")).isEqualTo(b);
    }

    @Test
    @DisplayName("refus : même produit 422, source avec stock 409, source fusionnée 409, cible archivée 409, inconnu 404")
    void merge_refusals() throws Exception {
        UUID a = product("Huile", null, null);
        UUID b = product("Huile 1L", null, null);
        UUID stocked = product("Sucre", null, null);
        assertStatus(push(stockEntryOp(stocked, 5)).get(0), "APPLIED");

        assertThat(merge(a, a).getResponse().getStatus()).isEqualTo(422);

        MvcResult withStock = merge(stocked, b);
        assertThat(withStock.getResponse().getStatus()).as(body(withStock)).isEqualTo(409);
        assertThat(json(withStock).path("domainCode").asText()).isEqualTo("PRODUCT_MERGE_INVALID");
        assertThat(json(withStock).path("error").asText())
                .isEqualTo("Ce produit a encore du stock : mettez-le à zéro avant de le fusionner");
        assertThat(row(stocked).get("archived")).isEqualTo(false);

        MvcResult unknown = merge(a, UUID.randomUUID());
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(unknown).path("domainCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");

        assertThat(merge(a, b).getResponse().getStatus()).isEqualTo(200);
        MvcResult again = merge(a, b);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).path("domainCode").asText()).isEqualTo("PRODUCT_MERGE_INVALID");

        UUID c = product("Huile 5L", null, null);
        MvcResult toArchived = merge(c, a); // a is archived (merged)
        assertThat(toArchived.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("hors ligne : vente sur A après fusion → appliquée sur B, rien de rejeté ; recréation par nom → B ; désarchivage → PRODUCT_MERGED")
    void offline_operations_after_merge() throws Exception {
        UUID a = product("Bissap", null, null);
        UUID b = product("Bissap 1L", null, null);
        assertStatus(push(stockEntryOp(b, 5)).get(0), "APPLIED");
        assertThat(merge(a, b).getResponse().getStatus()).isEqualTo(200);

        UUID saleId = UUID.randomUUID();
        JsonNode sale = push(saleOp(saleId, item(a, 2))).get(0);
        assertStatus(sale, "APPLIED");
        assertThat(sale.toString()).doesNotContain("PRODUCT_NOT_FOUND").doesNotContain("PRODUCT_MERGED");
        assertThat(jdbc.queryForObject("SELECT product_id FROM \"" + schema + "\".sale_items WHERE sale_id = ?",
                UUID.class, saleId)).isEqualTo(b);
        assertThat(level(b)).isEqualTo(3);
        assertThat(movementCount(a)).isZero();

        // REST sale on A lands on B as well
        MvcResult rest = restSale(item(a, 1));
        assertThat(rest.getResponse().getStatus()).as(body(rest)).isEqualTo(201);
        assertThat(level(b)).isEqualTo(2);

        // re-creation by name: online → 200 with B, A stays archived; offline → alias to B
        MvcResult recreated = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "bissap", "price", 900));
        assertThat(recreated.getResponse().getStatus()).as(body(recreated)).isEqualTo(200);
        assertThat(json(recreated).path("data").path("id").asText()).isEqualTo(b.toString());
        UUID local = UUID.randomUUID();
        JsonNode offline = push(createProductOp(local, "Bissap", null, null)).get(0);
        assertStatus(offline, "APPLIED");
        assertThat(offline.path("serverEntityId").asText()).isEqualTo(b.toString());
        assertThat(row(a).get("archived")).isEqualTo(true);

        // an unarchive made offline before the merge is superseded (LWW): A stays archived
        JsonNode stale = push(op("UNARCHIVE_PRODUCT", a.toString(),
                new LinkedHashMap<>(Map.of("productId", a.toString())))).get(0);
        assertThat(stale.path("status").asText()).isIn("APPLIED", "REJECTED");
        assertThat(row(a).get("archived")).isEqualTo(true);
        // made after the merge → refused with PRODUCT_MERGED
        clock = Instant.now().plusSeconds(60);
        JsonNode unarchive = push(op("UNARCHIVE_PRODUCT", a.toString(),
                new LinkedHashMap<>(Map.of("productId", a.toString())))).get(0);
        assertStatus(unarchive, "REJECTED");
        assertThat(unarchive.path("reason").asText()).isEqualTo("PRODUCT_MERGED");
        assertThat(row(a).get("archived")).isEqualTo(true);
    }

    // ── 17.12 non-regression (a)/(b) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("17.12 (a) : produit sans suivi vendu (REST et sync) → pas de contrôle, pas de mouvement, pas de ligne de stock")
    void untracked_sale_non_regression() throws Exception {
        UUID u = product("Livraison", false, null);

        MvcResult rest = restSale(item(u, 2));
        assertThat(rest.getResponse().getStatus()).as(body(rest)).isEqualTo(201);
        JsonNode synced = push(saleOp(UUID.randomUUID(), item(u, 5))).get(0);
        assertStatus(synced, "APPLIED");
        assertThat(synced.toString()).doesNotContain("INSUFFICIENT_STOCK").doesNotContain("STOCK_NEGATIVE");
        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();
    }

    @Test
    @DisplayName("17.12 (b) : activation avec 12 → « Stock de départ » STOCK_ENTRY ; activation à vide → aucun mouvement")
    void enable_tracking_non_regression() throws Exception {
        UUID withQuantity = product("Pagne", false, null);
        MvcResult r = call(MockMvcRequestBuilders.put("/api/v1/products/" + withQuantity + "/stock-tracking"),
                Map.of("tracked", true, "initialQuantity", 12, "storeId", storeId.toString()));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, Object> movement = jdbc.queryForMap("SELECT movement_type, quantity_change, notes FROM \""
                + schema + "\".stock_movements WHERE product_id = ?", withQuantity);
        assertThat(movement.get("movement_type")).isEqualTo("STOCK_ENTRY");
        assertThat(movement.get("quantity_change")).isEqualTo(12);
        assertThat(movement.get("notes")).isEqualTo(ChangeStockTrackingUseCase.INITIAL_STOCK_NOTE);
        assertThat(level(withQuantity)).isEqualTo(12);

        UUID empty = product("Bougie", false, null);
        MvcResult e = call(MockMvcRequestBuilders.put("/api/v1/products/" + empty + "/stock-tracking"),
                Map.of("tracked", true));
        assertThat(e.getResponse().getStatus()).as(body(e)).isEqualTo(200);
        assertThat(movementCount(empty)).isZero();
        assertThat(jdbc.queryForObject("SELECT track_stock FROM \"" + schema + "\".products WHERE id = ?",
                Boolean.class, empty)).isTrue();
        MvcResult refused = restSale(item(empty, 1));
        assertThat(json(refused).path("domainCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
    }
}
