package com.keevo.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowCommandPort;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.store.store.adapter.out.config.StoreLogoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.12 — extended stores (Bref fields, logo) and the default sale flow on the real chain: production security
 * filter (membership roles, VIEWER write guard), real use cases, sync push / pull, PostgreSQL. One test per group of
 * rows of the spec's I/O matrix.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.12 — extended stores and default sale flow (full context)")
class StoreBrefFieldsFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSaleFlowCommandPort saleFlowPort;
    @Autowired List<TenantFilesPurger> filePurgers;
    @Autowired StoreLogoProperties logoProperties;
    @Autowired TenantPreferencesRepository preferencesRepository;

    private String schema;
    private UUID storeId;
    private String owner;
    private String manager;
    private String employee;
    private String viewer;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".tenant_preferences DEFAULT VALUES");
        owner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        manager = member("MANAGER");
        employee = member("EMPLOYEE");
        viewer = member("VIEWER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private String member(String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ndiaye', false)", userId, storeId);
        return TestAccess.token(jdbc, jwtTokenProvider, userId, schema, role, "ACTIVE", storeId, false);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult upload(String token, UUID id, byte[] bytes, String contentType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo", contentType, bytes);
        return mockMvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/v1/stores/" + id + "/logo")
                .file(file).header("Authorization", "Bearer " + token)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r)).path("data");
    }

    private JsonNode error(MvcResult r, int status, String domainCode) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        JsonNode json = objectMapper.readTree(body(r));
        assertThat(json.path("domainCode").asText()).isEqualTo(domainCode);
        return json;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private JsonNode patch(Object... kv) throws Exception {
        return data(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeId, body(kv)), 200);
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private JsonNode pulledStore(String token, UUID id) throws Exception {
        String cursor = null;
        for (int pages = 0; pages < 50; pages++) {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) request.param("cursor", cursor);
            JsonNode d = objectMapper.readTree(body(mockMvc.perform(request).andReturn())).path("data");
            for (JsonNode s : d.path("entities").path("stores")) {
                if (s.path("id").asText().equals(id.toString())) return s;
            }
            if (!d.path("hasMore").asBoolean()) return null;
            cursor = d.path("nextCursor").asText();
        }
        return null;
    }

    private JsonNode push(Map<String, Object> op) throws Exception {
        MvcResult r = call(owner, HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op)));
        return data(r, 200).path("results").get(0);
    }

    private static Map<String, Object> op(String type, String entityId, Map<String, Object> payload) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", entityId);
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().toString());
        return op;
    }

    private Map<String, Object> storeRow() {
        return jdbc.queryForMap("SELECT category, description, logo_path, niu, rccm, country, updated_at FROM \""
                + schema + "\".stores WHERE id = ?", storeId);
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existing tenant: stores list and delta pull carry country CM and null Bref fields; preferences "
            + "carry defaultSaleFlow POS; previous fields unchanged")
    void existingTenantDefaults() throws Exception {
        JsonNode store = data(call(owner, HttpMethod.GET, "/api/v1/stores", null), 200).get(0);
        assertThat(store.path("country").asText()).isEqualTo("CM");
        for (String f : List.of("category", "description", "logoUrl", "niu", "rccm")) {
            assertThat(store.path(f).isNull()).as(f).isTrue();
        }
        assertThat(store.path("name").asText()).isNotBlank();
        assertThat(store.path("type").asText()).isEqualTo("STORE");
        assertThat(store.path("isActive").asBoolean()).isTrue();

        JsonNode pulled = pulledStore(owner, storeId);
        assertThat(pulled).isNotNull();
        assertThat(pulled.path("country").asText()).isEqualTo("CM");
        assertThat(pulled.path("logoUrl").isNull()).isTrue();
        assertThat(pulled.path("category").isNull()).isTrue();
        assertThat(pulled.path("name").asText()).isEqualTo(store.path("name").asText());

        for (String token : List.of(owner, manager, employee, viewer)) {
            JsonNode prefs = data(call(token, HttpMethod.GET, "/api/v1/tenant/preferences", null), 200);
            assertThat(prefs.path("defaultSaleFlow").asText()).isEqualTo("POS");
            assertThat(prefs.path("eodReportTime").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("PATCH: set (key, upper-cased country, trimmed niu), V1 body keeps them, empty clears, bad values "
            + "refused in French; the delta carries them; audited in French")
    void patchStore() throws Exception {
        JsonNode set = patch("name", "Boutique Awa", "category", "real_estate", "country", "ci", "niu", " M0123 ",
                "description", "Agence", "rccm", "RC/YAO/1");
        assertThat(set.path("category").asText()).isEqualTo("real_estate");
        assertThat(set.path("country").asText()).isEqualTo("CI");
        assertThat(set.path("niu").asText()).isEqualTo("M0123");

        JsonNode v1 = patch("name", "Boutique Awa 2", "address", "Douala", "phone", "+237690000001");
        assertThat(v1.path("name").asText()).isEqualTo("Boutique Awa 2");
        assertThat(v1.path("category").asText()).isEqualTo("real_estate");
        assertThat(v1.path("niu").asText()).isEqualTo("M0123");
        assertThat(v1.path("country").asText()).isEqualTo("CI");
        assertThat(v1.path("description").asText()).isEqualTo("Agence");

        JsonNode cleared = patch("name", "Boutique Awa 2", "description", "");
        assertThat(cleared.path("description").isNull()).isTrue();
        assertThat(cleared.path("rccm").asText()).isEqualTo("RC/YAO/1");
        assertThat(storeRow().get("description")).isNull();

        JsonNode bad = error(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeId,
                body("name", "Boutique", "category", "Alimentation")), 422, "VALIDATION_ERROR");
        assertThat(bad.path("error").asText()).isEqualTo("Catégorie de boutique inconnue");
        error(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeId, body("name", "Boutique", "country", "CMR")),
                422, "VALIDATION_ERROR");
        error(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeId, body("name", "Boutique", "country", "")),
                422, "VALIDATION_ERROR");
        error(call(owner, HttpMethod.PATCH, "/api/v1/stores/" + storeId,
                body("name", "Boutique", "niu", "n".repeat(31))), 422, "VALIDATION_ERROR");
        assertThat(storeRow().get("country")).isEqualTo("CI");

        JsonNode pulled = pulledStore(owner, storeId);
        assertThat(pulled.path("category").asText()).isEqualTo("real_estate");
        assertThat(pulled.path("country").asText()).isEqualTo("CI");
        assertThat(pulled.path("niu").asText()).isEqualTo("M0123");
        assertThat(pulled.path("rccm").asText()).isEqualTo("RC/YAO/1");

        String audit = jdbc.queryForObject("SELECT value_before || ' → ' || value_after FROM \"" + schema
                + "\".audit_log WHERE action = 'STORE_UPDATED' AND value_before IS NOT NULL "
                + "ORDER BY occurred_at LIMIT 1", String.class);
        assertThat(audit).contains("Immobilier").contains("\"country\":\"CI\"").doesNotContain("real_estate");

        assertThat(call(manager, HttpMethod.PATCH, "/api/v1/stores/" + storeId, body("name", "Boutique X"))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("POST with Bref fields → 201 with them; omitted → country CM, others null")
    void createStore() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET max_stores = 10");
        JsonNode created = data(call(owner, HttpMethod.POST, "/api/v1/stores",
                body("name", "Boutique Bref", "category", "beauty", "country", "ga", "rccm", "RC1")), 201);
        assertThat(created.path("category").asText()).isEqualTo("beauty");
        assertThat(created.path("country").asText()).isEqualTo("GA");
        assertThat(created.path("rccm").asText()).isEqualTo("RC1");
        JsonNode plain = data(call(owner, HttpMethod.POST, "/api/v1/stores", body("name", "Boutique V1")), 201);
        assertThat(plain.path("country").asText()).isEqualTo("CM");
        assertThat(plain.path("category").isNull()).isTrue();
    }

    @Test
    @DisplayName("sync: UPDATE_STORE / CREATE_STORE with a bad category REJECTED VALIDATION_ERROR; valid fields "
            + "APPLIED; a V1 UPDATE_STORE keeps them")
    void syncPush() throws Exception {
        JsonNode rejected = push(op("UPDATE_STORE", storeId.toString(),
                body("storeId", storeId.toString(), "name", "Boutique", "category", "Alimentation")));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asText()).isEqualTo("VALIDATION_ERROR");

        UUID newId = UUID.randomUUID();
        JsonNode rejectedCreate = push(op("CREATE_STORE", newId.toString(),
                body("id", newId.toString(), "name", "Boutique", "category", "Alimentation")));
        assertThat(rejectedCreate.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejectedCreate.path("reason").asText()).isEqualTo("VALIDATION_ERROR");

        JsonNode applied = push(op("UPDATE_STORE", storeId.toString(),
                body("storeId", storeId.toString(), "name", "Boutique", "category", "food", "country", "sn")));
        assertThat(applied.path("status").asText()).as(applied.toString()).isEqualTo("APPLIED");
        JsonNode v1 = push(op("UPDATE_STORE", storeId.toString(),
                body("storeId", storeId.toString(), "name", "Boutique renommée")));
        assertThat(v1.path("status").asText()).isEqualTo("APPLIED");
        Map<String, Object> row = storeRow();
        assertThat(row.get("category")).isEqualTo("food");
        assertThat(row.get("country")).isEqualTo("SN");
    }

    @Test
    @DisplayName("logo: owner PUT PNG → logoUrl, file, logo_path, updated_at bumped; every role reads it; invalid / "
            + "too large / unknown store / non-owner refused; DELETE clears; purge removes the files")
    void logo() throws Exception {
        Timestamp before = (Timestamp) storeRow().get("updated_at");

        JsonNode uploaded = data(upload(owner, storeId, png(900, 300), "image/png"), 200);
        String logoUrl = uploaded.path("logoUrl").asText();
        assertThat(logoUrl).startsWith("/api/v1/stores/" + storeId + "/logo?v=");
        Map<String, Object> row = storeRow();
        String logoPath = (String) row.get("logo_path");
        assertThat(logoPath).startsWith(storeId + "-").endsWith(".png");
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isAfter(before.toInstant());
        Path dir = Path.of(logoProperties.getStorageDir()).toAbsolutePath().normalize().resolve(schema);
        Path file = dir.resolve(logoPath);
        assertThat(file).exists();
        assertThat(pulledStore(owner, storeId).path("logoUrl").asText()).isEqualTo(logoUrl);
        assertThat(patch("name", "Boutique renommée").path("logoUrl").asText())
                .as("other edits keep the logo URL").isEqualTo(logoUrl);

        for (String token : List.of(owner, manager, employee, viewer)) {
            MvcResult image = call(token, HttpMethod.GET, "/api/v1/stores/" + storeId + "/logo", null);
            assertThat(image.getResponse().getStatus()).as(body(image)).isEqualTo(200);
            assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
            assertThat(image.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(image.getResponse().getHeader("Cache-Control")).contains("private").contains("max-age=2592000");
            BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(
                    image.getResponse().getContentAsByteArray()));
            assertThat(stored.getWidth()).isEqualTo(600);
        }

        MvcResult gif = upload(owner, storeId, "GIF89a-not-an-image-at-all".getBytes(), "image/gif");
        assertThat(error(gif, 422, "STORE_LOGO_INVALID").path("error").asText()).contains("PNG ou JPEG");
        error(upload(owner, storeId, "hello".repeat(10).getBytes(), "image/png"), 422, "STORE_LOGO_INVALID");
        error(upload(owner, storeId, new byte[250 * 1024], "image/png"), 422, "STORE_LOGO_TOO_LARGE");
        assertThat(data(upload(owner, storeId, png(30, 30), "image/jpg"), 200).path("logoUrl").asText())
                .as("image/jpg accepted; a new upload gets a new URL").isNotEqualTo(logoUrl);
        String replacedPath = (String) storeRow().get("logo_path");
        assertThat(file).as("previous file deleted after commit").doesNotExist();
        file = dir.resolve(replacedPath);
        assertThat(file).exists();
        error(upload(owner, UUID.randomUUID(), png(10, 10), "image/png"), 404, "STORE_NOT_FOUND");
        for (String token : List.of(manager, employee, viewer)) {
            assertThat(upload(token, storeId, png(10, 10), "image/png").getResponse().getStatus()).isEqualTo(403);
            assertThat(call(token, HttpMethod.DELETE, "/api/v1/stores/" + storeId + "/logo", null)
                    .getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(file).exists();

        assertThat(jdbc.queryForList("SELECT value_after FROM \"" + schema + "\".audit_log WHERE action = "
                + "'STORE_UPDATED' AND value_after LIKE '%logo%' ORDER BY occurred_at", String.class))
                .containsExactly("{\"logo\":\"Ajouté\"}", "{\"logo\":\"Remplacé\"}");

        JsonNode deleted = data(call(owner, HttpMethod.DELETE, "/api/v1/stores/" + storeId + "/logo", null), 200);
        assertThat(deleted.path("logoUrl").isNull()).isTrue();
        assertThat(storeRow().get("logo_path")).isNull();
        assertThat(file).doesNotExist();
        error(call(employee, HttpMethod.GET, "/api/v1/stores/" + storeId + "/logo", null), 404,
                "STORE_LOGO_NOT_FOUND");
        assertThat(data(call(owner, HttpMethod.DELETE, "/api/v1/stores/" + storeId + "/logo", null), 200)
                .path("logoUrl").isNull()).isTrue();

        data(upload(owner, storeId, png(20, 20), "image/png"), 200);
        file = dir.resolve((String) storeRow().get("logo_path"));
        assertThat(file).exists();
        filePurgers.forEach(p -> p.purgeTenant(schema));
        assertThat(file).doesNotExist();
        assertThat(Files.exists(file.getParent())).isFalse();
    }

    @Test
    @DisplayName("sale flow: owner PUT DOCUMENT (not gated by report-config) → GET DOCUMENT, audited Caisse → "
            + "Documents; other roles 403; invalid value 422; importer port sets DOCUMENT, owner switches back")
    void saleFlow() throws Exception {
        JsonNode set = data(call(owner, HttpMethod.PUT, "/api/v1/tenant/sale-flow",
                Map.of("defaultSaleFlow", "DOCUMENT")), 200);
        assertThat(set.path("defaultSaleFlow").asText()).isEqualTo("DOCUMENT");
        assertThat(data(call(employee, HttpMethod.GET, "/api/v1/tenant/preferences", null), 200)
                .path("defaultSaleFlow").asText()).isEqualTo("DOCUMENT");
        assertThat(jdbc.queryForObject("SELECT value_before || ' → ' || value_after FROM \"" + schema
                + "\".audit_log WHERE action = 'SALE_FLOW_UPDATED'", String.class))
                .contains("Caisse").contains("Documents").doesNotContain("DOCUMENT");

        for (String token : List.of(manager, employee, viewer)) {
            assertThat(call(token, HttpMethod.PUT, "/api/v1/tenant/sale-flow", Map.of("defaultSaleFlow", "POS"))
                    .getResponse().getStatus()).isEqualTo(403);
        }
        error(call(owner, HttpMethod.PUT, "/api/v1/tenant/sale-flow", Map.of("defaultSaleFlow", "CAISSE")), 422,
                "VALIDATION_ERROR");
        error(call(owner, HttpMethod.PUT, "/api/v1/tenant/sale-flow", Map.of()), 422, "VALIDATION_ERROR");

        data(call(owner, HttpMethod.PUT, "/api/v1/tenant/sale-flow", Map.of("defaultSaleFlow", "POS")), 200);
        TenantContext.clear();
        saleFlowPort.set(schema, SaleFlow.DOCUMENT); // importer, no tenant bound
        saleFlowPort.set(schema, SaleFlow.DOCUMENT); // same value: no-op
        assertThat(data(call(owner, HttpMethod.GET, "/api/v1/tenant/preferences", null), 200)
                .path("defaultSaleFlow").asText()).isEqualTo("DOCUMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema
                + "\".audit_log WHERE action = 'SALE_FLOW_UPDATED'", Integer.class)).isEqualTo(3);
        assertThat(data(call(owner, HttpMethod.PUT, "/api/v1/tenant/sale-flow",
                Map.of("defaultSaleFlow", "POS")), 200).path("defaultSaleFlow").asText()).isEqualTo("POS");

        assertThatThrownBy(() -> saleFlowPort.set("not a schema", SaleFlow.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("importer port on a tenant without preferences row → IllegalStateException, nothing audited; "
            + "preferences created with DOCUMENT are stored as DOCUMENT")
    void importerWithoutPreferences() {
        jdbc.update("DELETE FROM \"" + schema + "\".tenant_preferences");

        assertThatThrownBy(() -> saleFlowPort.set(schema, SaleFlow.DOCUMENT)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema
                + "\".audit_log WHERE action = 'SALE_FLOW_UPDATED'", Integer.class)).isZero();

        TenantContext.setCurrentTenant(schema);
        try {
            preferencesRepository.save(TenantPreferences.withDefaults(UUID.randomUUID(), SectorType.OTHER,
                    "20:00:00", true, Instant.now()).withDefaultSaleFlow(SaleFlow.DOCUMENT));
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT default_sale_flow FROM \"" + schema + "\".tenant_preferences",
                String.class)).isEqualTo("DOCUMENT");
    }
}
