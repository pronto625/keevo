package com.keevo.sync.sync.adapter.in.rest;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * Story 17.11 — optional initial stock at product creation, on the real sync chain ({@code POST /api/v1/sync/push} /
 * {@code GET /api/v1/sync/pull}, production security filter, a session issued by the production
 * {@link SessionTokenIssuer}, operation log, PostgreSQL). Pins the contract the React Native app relies on: the
 * device queues {@code CREATE_PRODUCT {id: X}} then, only when the owner typed a quantity Q &gt; 0,
 * {@code RECORD_STOCK_ENTRY {productId: X, storeId: S, quantity: Q}} with its local movement id as {@code entityId}.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("17.11 — initial stock at product creation through sync (full context)")
class InitialStockAtCreationFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;

    private String schema;
    private UUID tenantId;
    private String token;
    private UUID storeId;
    private final String deviceId = UUID.randomUUID().toString();
    private Instant clock = Instant.now().minusSeconds(600);

    @BeforeEach
    void setUp() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID ownerId = UUID.randomUUID();
        TestAccess.grant(jdbc, ownerId, schema, "OWNER");
        token = tx.execute(s -> issuer.issue(ownerId, tenant, "OWNER", null,
                DeviceInfo.of(deviceId, "Redmi")).tokens()).accessToken();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** A queued operation; client timestamps increase in queue order. */
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

    private Map<String, Object> createProduct(UUID id, String name, Integer legacyStockQuantity) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id.toString());
        p.put("name", name);
        p.put("price", 1500);
        if (legacyStockQuantity != null) p.put("stockQuantity", legacyStockQuantity);
        return op("CREATE_PRODUCT", id.toString(), p);
    }

    private Map<String, Object> stockEntry(UUID productId, UUID movementId, Object quantity) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("productId", productId.toString());
        p.put("storeId", storeId.toString());
        p.put("quantity", quantity);
        p.put("notes", "Stock initial");
        return op("RECORD_STOCK_ENTRY", movementId.toString(), p);
    }

    /** Pushes the operations as one batch; the results in request order. */
    @SafeVarargs
    private final List<JsonNode> push(Map<String, Object>... ops) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/sync/push")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("deviceId", deviceId,
                        "operations", List.of(ops))))).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : objectMapper.readTree(body(r)).path("data").path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        List<JsonNode> ordered = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            JsonNode result = byId.get((String) op.get("operationId"));
            assertThat(result).as("no result for %s %s in %s", op.get("operationType"), op.get("operationId"),
                    body(r)).isNotNull();
            ordered.add(result);
        }
        return ordered;
    }

    /** Every entity of a full pull (all pages), by entity type. */
    private Map<String, List<JsonNode>> pullAll() throws Exception {
        Map<String, List<JsonNode>> all = new LinkedHashMap<>();
        String cursor = null;
        int pages = 0;
        do {
            assertThat(++pages).as("pull did not end within 50 pages").isLessThanOrEqualTo(50);
            String previous = cursor;
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) request.param("cursor", cursor);
            MvcResult r = mockMvc.perform(request).andReturn();
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
            JsonNode data = objectMapper.readTree(body(r)).path("data");
            data.path("entities").fields().forEachRemaining(e ->
                    e.getValue().forEach(n -> all.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(n)));
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText(null) : null;
            if (data.path("hasMore").asBoolean()) {
                assertThat(cursor).as("hasMore without a new cursor").isNotBlank().isNotEqualTo(previous);
            }
        } while (cursor != null);
        return all;
    }

    private static List<JsonNode> ofProduct(Map<String, List<JsonNode>> pulled, String type, UUID productId) {
        return pulled.getOrDefault(type, List.of()).stream()
                .filter(n -> n.path("productId").asText().equals(productId.toString())).toList();
    }

    private int productCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".products", Integer.class);
    }

    private int movementCount(UUID productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".stock_movements WHERE product_id = ?",
                Integer.class, productId);
    }

    private Integer stockLevel(UUID productId) {
        List<Integer> q = jdbc.queryForList("SELECT quantity FROM \"" + schema + "\".stock_levels "
                + "WHERE product_id = ? AND store_id = ? AND variant_id IS NULL", Integer.class, productId, storeId);
        return q.isEmpty() ? null : q.get(0);
    }

    private static void assertApplied(JsonNode result) {
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("APPLIED");
    }

    // ── I/O matrix ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quantity given: CREATE_PRODUCT X + RECORD_STOCK_ENTRY X/S/5 → stock 5, one STOCK_ENTRY with the client's movement id, pulled")
    void quantityGiven() throws Exception {
        UUID x = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        // queue order on the device: product first, then its stock entry
        List<JsonNode> results = push(createProduct(x, "Savon Azur", null), stockEntry(x, movementId, 5));

        assertApplied(results.get(0));
        assertThat(results.get(0).path("serverEntityId").asText()).isEqualTo(x.toString());
        assertApplied(results.get(1));
        assertThat(stockLevel(x)).isEqualTo(5);
        assertThat(movementCount(x)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT movement_type FROM \"" + schema + "\".stock_movements WHERE id = ?",
                String.class, movementId)).isEqualTo("STOCK_ENTRY");

        Map<String, List<JsonNode>> pulled = pullAll();
        assertThat(pulled.getOrDefault("products", List.of()))
                .anyMatch(n -> n.path("id").asText().equals(x.toString()));
        List<JsonNode> movements = ofProduct(pulled, "stockMovements", x);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).path("id").asText()).isEqualTo(movementId.toString());
        assertThat(movements.get(0).path("movementType").asText()).isEqualTo("STOCK_ENTRY");
        assertThat(movements.get(0).path("quantityBefore").asInt()).isZero();
        assertThat(movements.get(0).path("quantityChange").asInt()).isEqualTo(5);
        assertThat(movements.get(0).path("notes").asText()).isEqualTo("Stock initial");
        assertThat(movements.get(0).path("storeId").asText()).isEqualTo(storeId.toString());
        assertThat(movements.get(0).path("quantityAfter").asInt()).isEqualTo(5);
        List<JsonNode> levels = ofProduct(pulled, "stockLevels", x);
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).path("storeId").asText()).isEqualTo(storeId.toString());
        assertThat(levels.get(0).path("quantity").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("stock entry queued BEFORE the product (clock skew) → the server still creates the product first")
    void stockEntryQueuedFirst_productHoisted() throws Exception {
        UUID x = UUID.randomUUID();
        Map<String, Object> entry = stockEntry(x, UUID.randomUUID(), 3);
        Map<String, Object> create = createProduct(x, "Lait Nido", null);

        List<JsonNode> results = push(entry, create);

        assertApplied(results.get(0));
        assertApplied(results.get(1));
        assertThat(stockLevel(x)).isEqualTo(3);
        assertThat(movementCount(x)).isEqualTo(1);
    }

    @Test
    @DisplayName("empty quantity: CREATE_PRODUCT X only → product created, no stock level row, no movement")
    void emptyQuantity() throws Exception {
        UUID x = UUID.randomUUID();
        int before = productCount();

        assertApplied(push(createProduct(x, "Bougie", null)).get(0));

        assertThat(productCount()).isEqualTo(before + 1);
        assertThat(stockLevel(x)).isNull();
        assertThat(movementCount(x)).isZero();
        Map<String, List<JsonNode>> pulled = pullAll();
        assertThat(ofProduct(pulled, "stockMovements", x)).isEmpty();
        assertThat(ofProduct(pulled, "stockLevels", x)).isEmpty();
    }

    @Test
    @DisplayName("replay: the same batch pushed twice → DUPLICATE, one product, one movement, stock still 5")
    void replay() throws Exception {
        UUID x = UUID.randomUUID();
        var create = createProduct(x, "Huile Mayor", null);
        var entry = stockEntry(x, UUID.randomUUID(), 5);
        int before = productCount();
        push(create, entry);

        List<JsonNode> again = push(create, entry);

        assertThat(again.get(0).path("status").asText()).as(again.get(0).toString()).isEqualTo("DUPLICATE");
        assertThat(again.get(1).path("status").asText()).as(again.get(1).toString()).isEqualTo("DUPLICATE");
        assertThat(productCount()).isEqualTo(before + 1);
        assertThat(movementCount(x)).isEqualTo(1);
        assertThat(stockLevel(x)).isEqualTo(5);
    }

    @Test
    @DisplayName("merged by name: « Riz » exists as E with 10; CREATE_PRODUCT X « riz » + RECORD_STOCK_ENTRY X/4 → E has 14, nothing for X")
    void mergedByName_stockLandsOnExisting() throws Exception {
        UUID e = UUID.randomUUID();
        push(createProduct(e, "Riz", null), stockEntry(e, UUID.randomUUID(), 10));
        assertThat(stockLevel(e)).isEqualTo(10);
        int before = productCount();
        UUID x = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        List<JsonNode> results = push(createProduct(x, "riz", null), stockEntry(x, movementId, 4));

        assertApplied(results.get(0));
        assertThat(results.get(0).path("serverEntityId").asText()).isEqualTo(e.toString());
        assertThat(results.get(0).path("reason").asText()).isEqualTo("MERGED_INTO:" + e);
        assertApplied(results.get(1));
        assertThat(productCount()).isEqualTo(before);
        assertThat(stockLevel(e)).isEqualTo(14);
        assertThat(movementCount(e)).isEqualTo(2);
        assertThat(movementCount(x)).isZero();
        assertThat(jdbc.queryForObject("SELECT product_id FROM \"" + schema + "\".stock_movements WHERE id = ?",
                UUID.class, movementId)).isEqualTo(e);

        Map<String, List<JsonNode>> pulled = pullAll();
        assertThat(pulled.getOrDefault("products", List.of()))
                .noneMatch(n -> n.path("id").asText().equals(x.toString()));
        assertThat(ofProduct(pulled, "stockLevels", x)).isEmpty();
        assertThat(ofProduct(pulled, "stockMovements", x)).isEmpty();
        assertThat(ofProduct(pulled, "stockLevels", e)).singleElement()
                .satisfies(n -> assertThat(n.path("quantity").asInt()).isEqualTo(14));
        assertThat(ofProduct(pulled, "stockMovements", e))
                .anySatisfy(n -> {
                    assertThat(n.path("id").asText()).isEqualTo(movementId.toString());
                    assertThat(n.path("quantityBefore").asInt()).isEqualTo(10);
                    assertThat(n.path("quantityAfter").asInt()).isEqualTo(14);
                });

        // a stock entry against X pushed in a LATER batch is still redirected to E
        assertApplied(push(stockEntry(x, UUID.randomUUID(), 2)).get(0));
        assertThat(stockLevel(e)).isEqualTo(16);
    }

    @Test
    @DisplayName("merged by name onto an ARCHIVED product: today the merge targets the archived E and the stock lands on it (E stays archived)")
    void mergedByName_ontoArchivedProduct_pinsTodaysBehaviour() throws Exception {
        UUID e = UUID.randomUUID();
        push(createProduct(e, "Riz", null));
        Map<String, Object> archive = op("ARCHIVE_PRODUCT", e.toString(), Map.of("productId", e.toString()));
        assertApplied(push(archive).get(0));
        int before = productCount();
        UUID x = UUID.randomUUID();

        List<JsonNode> results = push(createProduct(x, "Riz", null), stockEntry(x, UUID.randomUUID(), 4));

        assertApplied(results.get(0));
        assertThat(results.get(0).path("reason").asText()).isEqualTo("MERGED_INTO:" + e);
        assertApplied(results.get(1));
        assertThat(productCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT archived FROM \"" + schema + "\".products WHERE id = ?",
                Boolean.class, e)).isTrue();
        assertThat(stockLevel(e)).isEqualTo(4);
        assertThat(movementCount(x)).isZero();
    }

    @Test
    @DisplayName("same entry re-queued under a NEW operation id with the same movement id → DUPLICATE, no second entry")
    void sameMovementId_newOperationId_duplicate() throws Exception {
        UUID x = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        push(createProduct(x, "Thé Lipton", null), stockEntry(x, movementId, 5));

        JsonNode again = push(stockEntry(x, movementId, 5)).get(0);

        assertThat(again.path("status").asText()).as(again.toString()).isEqualTo("DUPLICATE");
        assertThat(movementCount(x)).isEqualTo(1);
        assertThat(stockLevel(x)).isEqualTo(5);
    }

    @Test
    @DisplayName("movement id already used by a different movement → REJECTED MOVEMENT_ID_ALREADY_USED, nothing written")
    void sameMovementId_differentContent_rejected() throws Exception {
        UUID x = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        push(createProduct(x, "Thé vert", null), stockEntry(x, movementId, 5));

        JsonNode other = push(stockEntry(x, movementId, 7)).get(0);

        assertThat(other.path("status").asText()).as(other.toString()).isEqualTo("REJECTED");
        assertThat(other.path("reason").asText()).isEqualTo("MOVEMENT_ID_ALREADY_USED");
        assertThat(other.path("conflictData").isNull()).isTrue();
        assertThat(movementCount(x)).isEqualTo(1);
        assertThat(stockLevel(x)).isEqualTo(5);
    }

    @Test
    @DisplayName("legacy CREATE_PRODUCT.stockQuantity 5 + RECORD_STOCK_ENTRY 5 → stock 5, not 10 (the legacy field creates no stock)")
    void legacyStockQuantity_notCountedTwice() throws Exception {
        UUID x = UUID.randomUUID();

        List<JsonNode> results = push(createProduct(x, "Sucre", 5), stockEntry(x, UUID.randomUUID(), 5));

        assertApplied(results.get(0));
        assertApplied(results.get(1));
        assertThat(stockLevel(x)).isEqualTo(5);
        assertThat(movementCount(x)).isEqualTo(1);
        Map<String, List<JsonNode>> pulled = pullAll();
        assertThat(ofProduct(pulled, "stockLevels", x)).singleElement()
                .satisfies(n -> assertThat(n.path("quantity").asInt()).isEqualTo(5));
    }

    @Test
    @DisplayName("legacy CREATE_PRODUCT.stockQuantity alone → no stock level row, no movement")
    void legacyStockQuantityAlone_createsNoStock() throws Exception {
        UUID x = UUID.randomUUID();

        assertApplied(push(createProduct(x, "Farine", 7)).get(0));

        assertThat(stockLevel(x)).isNull();
        assertThat(movementCount(x)).isZero();
    }

    @Test
    @DisplayName("negative / zero quantity → stock entry REJECTED with the existing reason, product kept, no stock")
    void nonPositiveQuantity_rejected() throws Exception {
        UUID x = UUID.randomUUID();
        int before = productCount();

        List<JsonNode> results = push(createProduct(x, "Café", null), stockEntry(x, UUID.randomUUID(), -2));
        List<JsonNode> zero = push(stockEntry(x, UUID.randomUUID(), 0));

        assertApplied(results.get(0));
        assertThat(results.get(1).path("status").asText()).as(results.get(1).toString()).isEqualTo("REJECTED");
        assertThat(results.get(1).path("reason").asText()).isEqualTo("Entry quantity must be positive");
        assertThat(results.get(1).path("conflictData").isNull()).isTrue();
        assertThat(zero.get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(zero.get(0).path("reason").asText()).isEqualTo("Entry quantity must be positive");
        assertThat(productCount()).isEqualTo(before + 1);
        assertThat(stockLevel(x)).isNull();
        assertThat(movementCount(x)).isZero();
    }

    @Test
    @DisplayName("creation refused (Free plan at its product limit) → the stock entry is REJECTED PRODUCT_NOT_FOUND, never applied")
    void creationRefused_stockEntryNotApplied() throws Exception {
        UUID template = UUID.randomUUID();
        push(createProduct(template, "Modèle", null));
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        Integer limit = jdbc.queryForObject(
                "SELECT max_products FROM public.plan_profiles WHERE plan_type = 'FREE'", Integer.class);
        assertThat(limit).as("FREE max_products").isNotNull().isGreaterThan(1);
        // copy every column except the id, the name, the sku and any other uniquely indexed column (barcode...)
        List<String> unique = jdbc.queryForList("SELECT a.attname FROM pg_index i JOIN pg_attribute a "
                + "ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = ?::regclass AND i.indisunique", String.class, "\"" + schema + "\".products");
        List<String> cols = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'products' AND column_name NOT IN "
                + "('id', 'name', 'sku', 'status', 'archived')", String.class, schema).stream()
                .filter(c -> !unique.contains(c)).toList();
        String list = String.join(", ", cols);
        int current = productCount();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status, archived, " + list + ") "
                + "SELECT gen_random_uuid(), 'Remplissage ' || g, 'KEV-F' || lpad(g::text, 5, '0'), 'ACTIVE', false, "
                + list + " FROM \"" + schema + "\".products, generate_series(1, ?) g WHERE id = ?",
                limit - current, template);
        assertThat(productCount()).isEqualTo(limit);
        UUID x = UUID.randomUUID();

        List<JsonNode> results = push(createProduct(x, "Produit de trop", null), stockEntry(x, UUID.randomUUID(), 5));

        assertThat(results.get(0).path("status").asText()).as(results.get(0).toString()).isEqualTo("REJECTED");
        assertThat(results.get(0).path("reason").asText()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        JsonNode details = results.get(0).path("conflictData");
        assertThat(details.path("entity").asText()).as(details.toString()).isEqualTo("products");
        assertThat(details.path("limit").asInt()).isEqualTo(limit);
        assertThat(details.path("current").asInt()).isEqualTo(limit);
        assertThat(results.get(1).path("status").asText()).as(results.get(1).toString()).isEqualTo("REJECTED");
        assertThat(results.get(1).path("reason").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(movementCount(x)).isZero();
        assertThat(stockLevel(x)).isNull();
        assertThat(productCount()).isEqualTo(limit);
    }
}
