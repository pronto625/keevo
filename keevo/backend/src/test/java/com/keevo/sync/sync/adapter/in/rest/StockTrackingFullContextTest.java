package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.SessionTokenIssuer;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.DeviceInfo;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.inventory.stock.adapter.out.export.StockLevelsExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantFoundation;
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
 * Story 17.12 — per-product stock tracking ({@code products.track_stock}) on the real chain: REST controllers and
 * {@code POST /api/v1/sync/push} / {@code GET /api/v1/sync/pull} behind the production security filter (session from
 * the production {@link SessionTokenIssuer}), real use cases, PostgreSQL. One test per row of the spec's I/O matrix.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("17.12 — stock tracking per product (full context)")
class StockTrackingFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired SessionTokenIssuer issuer;
    @Autowired StockLevelsExportDatasetProvider stockExport;
    @Autowired javax.sql.DataSource foundationDataSource;
    @Autowired com.keevo.catalog.product.adapter.out.export.ProductsExportDatasetProvider productsExport;
    @Autowired com.keevo.reporting.dashboard.application.service.DashboardService dashboardService;
    @Autowired com.keevo.reporting.report.application.service.EndOfDayReportBuilder endOfDay;
    @Autowired com.keevo.reporting.report.application.service.WeeklyReportBuilder weeklyReport;

    private String schema;
    private UUID tenantId;
    private UUID ownerId;
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
        ownerId = UUID.randomUUID();
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

    private void moduleRow(String moduleId, String status) {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?", tenantId, moduleId);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE')",
                tenantId, moduleId, status);
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

    private Map<String, Object> createProductOp(UUID id, String name, Boolean trackStock) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id.toString());
        p.put("name", name);
        p.put("price", 1000);
        if (trackStock != null) p.put("trackStock", trackStock);
        return op("CREATE_PRODUCT", id.toString(), p);
    }

    private Map<String, Object> stockEntryOp(UUID productId, UUID movementId, int quantity) {
        return op("RECORD_STOCK_ENTRY", movementId.toString(), new LinkedHashMap<>(Map.of(
                "productId", productId.toString(), "storeId", storeId.toString(), "quantity", quantity)));
    }

    private Map<String, Object> trackingOp(UUID productId, boolean tracked, Integer quantity, UUID movementId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("productId", productId.toString());
        p.put("tracked", tracked);
        if (quantity != null) {
            p.put("initialQuantity", quantity);
            p.put("storeId", storeId.toString());
        }
        if (movementId != null) p.put("movementId", movementId.toString());
        return op("SET_PRODUCT_STOCK_TRACKING", productId.toString(), p);
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
    private Map<String, Object> saleOp(Map<String, Object>... items) {
        UUID saleId = UUID.randomUUID();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("saleId", saleId.toString());
        p.put("storeId", storeId.toString());
        p.put("paymentMode", "CASH");
        p.put("items", List.of(items));
        return op("CREATE_SALE", saleId.toString(), p);
    }

    /** REST sale ({@code POST /api/v1/sales}); returns the HTTP response. */
    @SafeVarargs
    private MvcResult restSale(String status, Map<String, Object>... items) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("saleId", UUID.randomUUID().toString());
        s.put("paymentMode", "CASH");
        s.put("storeId", storeId.toString());
        if (status != null) s.put("status", status);
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

    /** A product created through sync (V1-style when {@code trackStock} is null). */
    private UUID product(String name, Boolean trackStock) throws Exception {
        UUID id = UUID.randomUUID();
        assertStatus(push(createProductOp(id, name, trackStock)).get(0), "APPLIED");
        return id;
    }

    private UUID trackedWithStock(String name, int quantity) throws Exception {
        UUID id = product(name, null);
        assertStatus(push(stockEntryOp(id, UUID.randomUUID(), quantity)).get(0), "APPLIED");
        return id;
    }

    private boolean trackStock(UUID productId) {
        return jdbc.queryForObject("SELECT track_stock FROM \"" + schema + "\".products WHERE id = ?",
                Boolean.class, productId);
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

    private List<String> auditActions(UUID productId) {
        return jdbc.queryForList("SELECT action FROM \"" + schema + "\".audit_log WHERE entity_id = ? "
                + "ORDER BY occurred_at", String.class, productId);
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
    @DisplayName("untracked sale: no stock, sold online and through sync → accepted, no movement, no stock row, no STOCK_NEGATIVE")
    void untrackedSale() throws Exception {
        UUID u = product("Livraison", false);
        assertThat(trackStock(u)).isFalse();
        assertThat(pulledProduct(u).path("trackStock").asBoolean(true)).isFalse();

        MvcResult rest = restSale(null, item(u, 2));
        assertThat(rest.getResponse().getStatus()).as(body(rest)).isEqualTo(201);

        JsonNode synced = push(saleOp(item(u, 5))).get(0);
        assertStatus(synced, "APPLIED");
        assertThat(synced.toString()).doesNotContain("STOCK_NEGATIVE").doesNotContain("INSUFFICIENT_STOCK");

        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();
    }

    @Test
    @DisplayName("mixed sale: tracked T (stock 3) ×2 + untracked U ×5 → T at 1, U untouched; T over stock → INSUFFICIENT_STOCK")
    void mixedSale() throws Exception {
        UUID t = trackedWithStock("Savon", 3);
        UUID u = product("Retouche", false);

        MvcResult ok = restSale(null, item(t, 2), item(u, 5));
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(201);
        assertThat(level(t)).isEqualTo(1);
        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();

        MvcResult refused = restSale(null, item(t, 2), item(u, 1));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(422);
        assertThat(json(refused).path("domainCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(level(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("exclusions: untracked product absent from store detail, overview value, inventory session, stock export; transfer refused in French")
    void exclusions() throws Exception {
        moduleRow("multi-store", "ACTIVE");
        moduleRow("counting", "ACTIVE");
        UUID t = trackedWithStock("Riz", 4);
        UUID u = trackedWithStock("Service couture", 6);
        assertStatus(push(trackingOp(u, false, null, null)).get(0), "APPLIED"); // frozen at 6
        assertThat(level(u)).isEqualTo(6);

        String detail = body(call(MockMvcRequestBuilders.get("/api/v1/stock/stores/" + storeId + "/products"), null));
        assertThat(detail).contains(t.toString()).doesNotContain(u.toString());
        JsonNode overview = json(call(MockMvcRequestBuilders.get("/api/v1/stock/overview"), null));
        JsonNode boutique = null;
        for (JsonNode o : overview.path("data")) {
            if (o.path("storeId").asText().equals(storeId.toString())) boutique = o;
        }
        assertThat(boutique).as(overview.toString()).isNotNull();
        assertThat(boutique.path("totalValueXaf").asLong()).as("only T: 4 × 1000").isEqualTo(4000L);
        assertThat(boutique.path("productCount").asInt()).isEqualTo(1);

        JsonNode availability = json(call(MockMvcRequestBuilders.get("/api/v1/stock/products/" + u + "/availability"), null));
        assertThat(availability.toString()).as("no frozen level shown for U").doesNotContain("\"quantity\":6");

        MvcResult session = call(MockMvcRequestBuilders.post("/api/v1/inventory/sessions"),
                Map.of("storeId", storeId.toString(), "scope", "FULL"));
        assertThat(session.getResponse().getStatus()).as(body(session)).isIn(200, 201);
        String sessionId = json(session).path("data").path("id").asText();
        String rows = body(call(MockMvcRequestBuilders.get("/api/v1/inventory/sessions/" + sessionId + "/products"), null));
        assertThat(rows).contains(t.toString()).doesNotContain(u.toString());
        call(MockMvcRequestBuilders.post("/api/v1/inventory/sessions/" + sessionId + "/cancel"), null);

        TenantContext.setCurrentTenant(schema);
        try {
            var page = tx.execute(s -> stockExport.readPage(ExportFilter.none(), null, 100));
            assertThat(page.rows()).hasSize(1);
            assertThat(page.rows().get(0).get(2)).isEqualTo("Riz");
        } finally {
            TenantContext.clear();
        }

        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type) VALUES (?, 'Annexe', 'STORE')", other);
        MvcResult transfer = call(MockMvcRequestBuilders.post("/api/v1/stock/transfers"), Map.of(
                "sourceStoreId", storeId.toString(), "destinationStoreId", other.toString(),
                "productId", u.toString(), "quantity", 1));
        assertThat(transfer.getResponse().getStatus()).as(body(transfer)).isEqualTo(422);
        assertThat(json(transfer).path("domainCode").asText()).isEqualTo("STOCK_NOT_TRACKED");
        assertThat(json(transfer).path("error").asText()).isEqualTo("Le suivi du stock est désactivé pour ce produit");

        JsonNode syncTransfer = push(op("STOCK_TRANSFER", UUID.randomUUID().toString(), new LinkedHashMap<>(Map.of(
                "sourceStoreId", storeId.toString(), "destinationStoreId", other.toString(),
                "productId", u.toString(), "quantity", 1)))).get(0);
        assertStatus(syncTransfer, "REJECTED");
        assertThat(syncTransfer.path("reason").asText()).isEqualTo("STOCK_NOT_TRACKED");
        assertThat(level(u)).isEqualTo(6);
    }

    @Test
    @DisplayName("enable with 12 (REST): STOCK_ENTRY 12 in the active store, other stores 0, next sale checked; audit FR")
    void enableWithQuantity_rest() throws Exception {
        UUID u = product("Pagne", false);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type) VALUES (?, 'Annexe', 'STORE')", other);

        MvcResult r = call(MockMvcRequestBuilders.put("/api/v1/products/" + u + "/stock-tracking"),
                Map.of("tracked", true, "initialQuantity", 12, "storeId", storeId.toString()));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        JsonNode data = json(r).path("data");
        assertThat(data.path("trackStock").asBoolean()).isTrue();
        assertThat(data.path("initialEntry").path("movementType").asText()).isEqualTo("STOCK_ENTRY");
        assertThat(data.path("initialEntry").path("quantityAfter").asInt()).isEqualTo(12);

        assertThat(trackStock(u)).isTrue();
        assertThat(level(u)).isEqualTo(12);
        assertThat(levelRows(u)).isEqualTo(1); // the other store has no row: 0
        assertThat(movementCount(u)).isEqualTo(1);
        assertThat(auditActions(u)).contains("STOCK_TRACKING_ENABLED");

        MvcResult refused = restSale(null, item(u, 13));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(422);
        MvcResult sold = restSale(null, item(u, 2));
        assertThat(sold.getResponse().getStatus()).as(body(sold)).isEqualTo(201);
        assertThat(level(u)).isEqualTo(10);
    }

    @Test
    @DisplayName("enable with 12 (sync, client movement id): entry kept with the device id; same movement under a new operation id → DUPLICATE")
    void enableWithQuantity_sync() throws Exception {
        UUID u = product("Tissu", false);
        UUID movementId = UUID.randomUUID();

        assertStatus(push(trackingOp(u, true, 12, movementId)).get(0), "APPLIED");
        assertThat(level(u)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT movement_type FROM \"" + schema + "\".stock_movements WHERE id = ?",
                String.class, movementId)).isEqualTo("STOCK_ENTRY");

        assertStatus(push(trackingOp(u, true, 12, movementId)).get(0), "DUPLICATE");
        JsonNode reused = push(trackingOp(u, true, 7, movementId)).get(0);
        assertStatus(reused, "REJECTED");
        assertThat(reused.path("reason").asText()).isEqualTo("MOVEMENT_ID_ALREADY_USED");
        assertThat(level(u)).isEqualTo(12);
        assertThat(movementCount(u)).isEqualTo(1);
    }

    @Test
    @DisplayName("enable empty: tracked, 0 everywhere, no movement; the next sale is checked")
    void enableEmpty() throws Exception {
        UUID u = product("Bougie", false);

        assertStatus(push(trackingOp(u, true, null, null)).get(0), "APPLIED");
        assertThat(trackStock(u)).isTrue();
        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();

        MvcResult refused = restSale(null, item(u, 1));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(422);
        assertThat(json(refused).path("domainCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    @DisplayName("disable then re-enable: sales move nothing, levels frozen, history readable, audit FR; re-enabling resumes from the frozen level")
    void disableThenReenable() throws Exception {
        UUID t = trackedWithStock("Huile", 5);

        MvcResult off = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", false));
        assertThat(off.getResponse().getStatus()).as(body(off)).isEqualTo(200);
        assertThat(trackStock(t)).isFalse();

        assertThat(restSale(null, item(t, 3)).getResponse().getStatus()).isEqualTo(201);
        assertStatus(push(saleOp(item(t, 9))).get(0), "APPLIED"); // more than the frozen 5: no check
        assertThat(level(t)).isEqualTo(5);
        assertThat(movementCount(t)).isEqualTo(1);

        JsonNode history = json(call(MockMvcRequestBuilders.get("/api/v1/products/" + t + "/stock/history"), null));
        assertThat(history.path("data").path("content")).hasSize(1);
        assertThat(auditActions(t)).contains("STOCK_TRACKING_DISABLED");
        assertThat(jdbc.queryForObject("SELECT entity_label FROM \"" + schema + "\".audit_log WHERE entity_id = ? "
                + "AND action = 'STOCK_TRACKING_DISABLED'", String.class, t)).isEqualTo("Huile");

        // re-enable (sync op, no quantity): resumes from 5
        assertStatus(push(trackingOp(t, true, null, null)).get(0), "APPLIED");
        assertThat(level(t)).isEqualTo(5);
        assertThat(restSale(null, item(t, 6)).getResponse().getStatus()).isEqualTo(422);
        assertThat(restSale(null, item(t, 2)).getResponse().getStatus()).isEqualTo(201);
        assertThat(level(t)).isEqualTo(3);
    }

    @Test
    @DisplayName("cancellation and correction of a sale of an untracked product: no restock, no adjustment")
    void cancelAndCorrect_untracked() throws Exception {
        UUID u = product("Repassage", false);
        UUID saleId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Map<String, Object> line = item(u, 4);
        line.put("itemId", itemId.toString());
        MvcResult sold = call(MockMvcRequestBuilders.post("/api/v1/sales"), Map.of("saleId", saleId.toString(),
                "paymentMode", "CASH", "storeId", storeId.toString(), "items", List.of(line)));
        assertThat(sold.getResponse().getStatus()).as(body(sold)).isEqualTo(201);

        MvcResult corrected = call(MockMvcRequestBuilders.post("/api/v1/sales/" + saleId + "/correct"),
                Map.of("justification", "Erreur de saisie client", "itemQuantities", Map.of(itemId.toString(), 2)));
        assertThat(corrected.getResponse().getStatus()).as(body(corrected)).isEqualTo(200);
        MvcResult cancelled = call(MockMvcRequestBuilders.post("/api/v1/sales/" + saleId + "/cancel"),
                Map.of("justification", "Client parti sans payer"));
        assertThat(cancelled.getResponse().getStatus()).as(body(cancelled)).isEqualTo(200);

        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();
    }

    @Test
    @DisplayName("pending sale validation with an untracked product: validated, nothing forced to zero, no movement")
    void pendingValidation_untracked() throws Exception {
        UUID u = product("Emballage cadeau", false);
        UUID saleId = UUID.randomUUID();
        MvcResult pending = call(MockMvcRequestBuilders.post("/api/v1/sales"), Map.of("saleId", saleId.toString(),
                "paymentMode", "CASH", "storeId", storeId.toString(), "status", "PENDING_VALIDATION",
                "items", List.of(item(u, 3))));
        assertThat(pending.getResponse().getStatus()).as(body(pending)).isEqualTo(201);

        MvcResult validated = call(MockMvcRequestBuilders.post("/api/v1/sales/" + saleId + "/validate"),
                Map.of("justification", "Validation manuelle du gérant"));
        assertThat(validated.getResponse().getStatus()).as(body(validated)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".sales WHERE id = ?",
                String.class, saleId)).isEqualTo("COMPLETED");
        assertThat(movementCount(u)).isZero();
        assertThat(auditActions(u)).doesNotContain("STOCK_FORCED_ZERO");
    }

    @Test
    @DisplayName("inventory module disabled: a tracked product with no stock sells online and through sync, no movement")
    void noInventoryModule() throws Exception {
        UUID t = product("Lait", null);
        assertThat(trackStock(t)).isTrue();
        moduleRow("inventory", "DISABLED");

        assertThat(restSale(null, item(t, 2)).getResponse().getStatus()).isEqualTo(201);
        JsonNode synced = push(saleOp(item(t, 3))).get(0);
        assertStatus(synced, "APPLIED");
        assertThat(synced.toString()).doesNotContain("STOCK_NEGATIVE");
        assertThat(movementCount(t)).isZero();
        assertThat(levelRows(t)).isZero();
    }

    @Test
    @DisplayName("existing tenant: the migration adds track_stock = true to existing products; behaviour unchanged")
    void migrationOnExistingTenant() throws Exception {
        UUID old = UUID.randomUUID();
        jdbc.execute("ALTER TABLE \"" + schema + "\".products DROP COLUMN track_stock");
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, price) VALUES (?, 'Ancien', 'KEV-OLD001', 500)",
                old);

        TenantFoundation.upgrade(foundationDataSource, schema);

        assertThat(trackStock(old)).isTrue();
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'products' AND column_name = 'track_stock'", String.class, schema)).isEqualTo("true");
        assertStatus(push(stockEntryOp(old, UUID.randomUUID(), 1)).get(0), "APPLIED");
        MvcResult refused = restSale(null, item(old, 2));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(422);
    }

    @Test
    @DisplayName("V1 payload: CREATE_PRODUCT without trackStock → tracked; REST product shows trackStock")
    void v1Payload() throws Exception {
        UUID p = product("Sucre", null);
        assertThat(trackStock(p)).isTrue();
        assertThat(pulledProduct(p).path("trackStock").asBoolean(false)).isTrue();

        MvcResult created = call(MockMvcRequestBuilders.post("/api/v1/products"),
                Map.of("name", "Consultation", "price", 5000, "trackStock", false));
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        assertThat(json(created).path("data").path("trackStock").asBoolean(true)).isFalse();
        UUID id = UUID.fromString(json(created).path("data").path("id").asText());

        MvcResult patched = call(MockMvcRequestBuilders.patch("/api/v1/products/" + id),
                Map.of("trackStock", true));
        assertThat(patched.getResponse().getStatus()).as(body(patched)).isEqualTo(200);
        assertThat(json(patched).path("data").path("trackStock").asBoolean(false)).isTrue();
        assertThat(auditActions(id)).contains("STOCK_TRACKING_ENABLED");
    }

    @Test
    @DisplayName("entry on an untracked product: RECORD_STOCK_ENTRY → REJECTED STOCK_NOT_TRACKED; REST entry → 422")
    void entryOnUntracked() throws Exception {
        UUID u = product("Coupe", false);

        JsonNode rejected = push(stockEntryOp(u, UUID.randomUUID(), 5)).get(0);
        assertStatus(rejected, "REJECTED");
        assertThat(rejected.path("reason").asText()).isEqualTo("STOCK_NOT_TRACKED");

        MvcResult rest = call(MockMvcRequestBuilders.post("/api/v1/products/" + u + "/stock/entry"),
                Map.of("storeId", storeId.toString(), "quantity", 5));
        assertThat(rest.getResponse().getStatus()).as(body(rest)).isEqualTo(422);
        assertThat(json(rest).path("domainCode").asText()).isEqualTo("STOCK_NOT_TRACKED");
        assertThat(movementCount(u)).isZero();
        assertThat(levelRows(u)).isZero();
    }

    @Test
    @DisplayName("invalid tracking requests: quantity on a disable / negative quantity → 422 VALIDATION_ERROR, nothing changed")
    void invalidRequests() throws Exception {
        UUID t = trackedWithStock("Farine", 2);
        MvcResult qtyOnDisable = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", false, "initialQuantity", 3, "storeId", storeId.toString()));
        assertThat(qtyOnDisable.getResponse().getStatus()).as(body(qtyOnDisable)).isEqualTo(422);
        assertThat(json(qtyOnDisable).path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(trackStock(t)).isTrue();

        JsonNode negative = push(trackingOp(t, true, -1, null)).get(0);
        assertStatus(negative, "REJECTED");
        assertThat(negative.path("reason").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(level(t)).isEqualTo(2);

        // unknown store with an already-tracked product: refused, nothing written
        MvcResult unknownStore = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", true, "initialQuantity", 3, "storeId", UUID.randomUUID().toString()));
        assertThat(unknownStore.getResponse().getStatus()).as(body(unknownStore)).isEqualTo(404);
        assertThat(json(unknownStore).path("domainCode").asText()).isEqualTo("STORE_NOT_FOUND");
        UUID closed = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active) VALUES (?, 'Fermée', 'STORE', false)",
                closed);
        MvcResult inactive = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", true, "initialQuantity", 3, "storeId", closed.toString()));
        assertThat(inactive.getResponse().getStatus()).as(body(inactive)).isEqualTo(422);
        assertThat(json(inactive).path("domainCode").asText()).isEqualTo("STORE_NOT_ACTIVE");
        assertThat(level(t)).isEqualTo(2);
        assertThat(movementCount(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrency: two simultaneous activations with 12 → one STOCK_ENTRY, stock 12, one audit entry")
    void concurrentActivations() throws Exception {
        UUID u = product("Perles", false);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    barrier.await();
                    return call(MockMvcRequestBuilders.put("/api/v1/products/" + u + "/stock-tracking"),
                            Map.of("tracked", true, "initialQuantity", 12, "storeId", storeId.toString()))
                            .getResponse().getStatus();
                }));
            }
            for (var c : calls) assertThat(c.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(level(u)).isEqualTo(12);
        assertThat(movementCount(u)).isEqualTo(1);
        assertThat(auditActions(u).stream().filter("STOCK_TRACKING_ENABLED"::equals)).hasSize(1);
    }

    @Test
    @DisplayName("sync: enable with a quantity on an already-tracked product → APPLIED, initialEntry IGNORED, nothing added")
    void enableWithQuantity_alreadyTracked_ignored() throws Exception {
        UUID t = trackedWithStock("Café", 4);
        JsonNode result = push(trackingOp(t, true, 12, UUID.randomUUID())).get(0);
        assertStatus(result, "APPLIED");
        assertThat(result.path("conflictData").path("initialEntry").asText()).isEqualTo("IGNORED");
        assertThat(level(t)).isEqualTo(4);
        assertThat(movementCount(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("disable refused while a transfer of the product is in transit; allowed once it is received")
    void disableWithTransferInTransit() throws Exception {
        moduleRow("multi-store", "ACTIVE");
        UUID t = trackedWithStock("Gaz", 5);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type) VALUES (?, 'Annexe', 'STORE')", other);
        MvcResult transfer = call(MockMvcRequestBuilders.post("/api/v1/stock/transfers"), Map.of(
                "sourceStoreId", storeId.toString(), "destinationStoreId", other.toString(),
                "productId", t.toString(), "quantity", 2));
        assertThat(transfer.getResponse().getStatus()).as(body(transfer)).isEqualTo(201);
        String transferId = json(transfer).path("data").path("id").asText();

        MvcResult refused = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", false));
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
        assertThat(json(refused).path("domainCode").asText()).isEqualTo("TRANSFER_IN_TRANSIT");
        assertThat(json(refused).path("error").asText())
                .isEqualTo("Un transfert de ce produit est en cours : réceptionnez-le avant de désactiver le suivi du stock");
        JsonNode syncRefused = push(trackingOp(t, false, null, null)).get(0);
        assertStatus(syncRefused, "REJECTED");
        assertThat(syncRefused.path("reason").asText()).isEqualTo("TRANSFER_IN_TRANSIT");
        assertThat(trackStock(t)).isTrue();

        MvcResult received = call(MockMvcRequestBuilders.post("/api/v1/stock/transfers/" + transferId + "/complete"), null);
        assertThat(received.getResponse().getStatus()).as(body(received)).isEqualTo(200);
        MvcResult off = call(MockMvcRequestBuilders.put("/api/v1/products/" + t + "/stock-tracking"),
                Map.of("tracked", false));
        assertThat(off.getResponse().getStatus()).as(body(off)).isEqualTo(200);
        assertThat(trackStock(t)).isFalse();
    }

    @Test
    @DisplayName("sync UPDATE_PRODUCT trackStock:false → untracked, one « Suivi du stock désactivé », no « Produit modifié »")
    void updateProduct_trackStockOnly() throws Exception {
        UUID t = trackedWithStock("Thé", 3);
        String description = jdbc.queryForObject("SELECT description FROM \"" + schema + "\".products WHERE id = ?",
                String.class, t);

        assertStatus(push(op("UPDATE_PRODUCT", t.toString(), new LinkedHashMap<>(Map.of(
                "productId", t.toString(), "trackStock", false)))).get(0), "APPLIED");

        assertThat(trackStock(t)).isFalse();
        assertThat(auditActions(t)).contains("STOCK_TRACKING_DISABLED").doesNotContain("PRODUCT_UPDATED");
        assertThat(jdbc.queryForObject("SELECT description FROM \"" + schema + "\".products WHERE id = ?",
                String.class, t)).isEqualTo(description);

        JsonNode invalid = push(op("UPDATE_PRODUCT", t.toString(), new LinkedHashMap<>(Map.of(
                "productId", t.toString(), "trackStock", "oui")))).get(0);
        assertStatus(invalid, "REJECTED");
        assertThat(invalid.path("reason").asText()).isEqualTo("Invalid trackStock: must be a boolean");
    }

    @Test
    @DisplayName("untracked product with stockQuantity: the legacy column stays 0; products export: empty stock, « Suivi du stock » = false")
    void legacyColumnAndProductsExport() throws Exception {
        UUID u = UUID.randomUUID();
        Map<String, Object> create = createProductOp(u, "Retouche", false);
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) create.get("payload");
        p.put("stockQuantity", 9);
        assertStatus(push(create).get(0), "APPLIED");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM \"" + schema + "\".products WHERE id = ?",
                Integer.class, u)).isZero();

        TenantContext.setCurrentTenant(schema);
        try {
            var page = tx.execute(s -> productsExport.readPage(ExportFilter.none(), null, 100));
            var row = page.rows().stream().filter(r -> "Retouche".equals(r.get(1))).findFirst().orElseThrow();
            int stockCol = indexOf(productsExport.columns(), "stockQuantity");
            int trackCol = indexOf(productsExport.columns(), "trackStock");
            assertThat(row.get(stockCol)).isNull();
            assertThat(row.get(trackCol)).isEqualTo(false);
        } finally {
            TenantContext.clear();
        }
    }

    private static int indexOf(List<com.keevo.shared.application.port.export.ExportColumn> columns, String key) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).key().equals(key)) return i;
        throw new AssertionError("no column " + key);
    }

    @Test
    @DisplayName("frozen-low untracked product: not counted in dashboard, end-of-day or weekly low stock")
    void lowStockCounts() throws Exception {
        UUID t = trackedWithStock("Riz bas", 1);           // low (≤ 5)
        UUID u = trackedWithStock("Service bas", 2);       // low, then frozen
        assertStatus(push(trackingOp(u, false, null, null)).get(0), "APPLIED");

        TenantContext.setCurrentTenant(schema);
        try {
            int dashboard = tx.execute(s -> dashboardService.execute(
                    new com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase.GetDashboardSummaryQuery(schema)))
                    .lowStockCount();
            var window = com.keevo.commerce.api.ClosureWindow.between(Instant.now().minusSeconds(3600), Instant.now());
            int eod = tx.execute(s -> endOfDay.build(storeId, window, "Boutique", java.time.LocalDate.now(),
                    java.time.LocalTime.NOON, false)).lowStockCount();
            int weekly = tx.execute(s -> weeklyReport.build(storeId, window, "Boutique", java.time.LocalDate.now(),
                    schema, false)).lowStockCount();
            assertThat(dashboard).isEqualTo(1);
            assertThat(eod).isEqualTo(1);
            assertThat(weekly).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        assertThat(level(t)).isEqualTo(1);
    }
}
