package com.keevo.invoicing;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.6 — derivation of the CRM client and catalogue products of an issued invoice on the real chain (REST,
 * capability registry, crm and catalog modules, PostgreSQL): the acceptance criteria of the spec — new client and
 * products on a DOCUMENT tenant with unchanged totals, reuse on a second invoice, no derivation for a POS tenant, an
 * explicit « off », a disabled module, quote conversion, and the settings preference (resolved, V1 body unchanged).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("20.6 — dérivation clients et produits depuis la facture (full context)")
class DocumentDerivationFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwt;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;
    private String owner;
    private UUID oldestStore;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        owner = TestAccess.token(jdbc, jwt, UUID.randomUUID(), schema, "OWNER");
        oldestStore = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at, id LIMIT 1",
                UUID.class);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ? AND module_id IN ('crm', 'catalog')",
                tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private void saleFlow(String flow) {
        jdbc.update("DELETE FROM \"" + schema + "\".tenant_preferences");
        jdbc.update("INSERT INTO \"" + schema + "\".tenant_preferences (default_sale_flow) VALUES (?)", flow);
    }

    private void module(String id, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE') "
                + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status", tenantId, id, status);
        registry.evict(schema);
    }

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        return call(owner, method, path, body);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return objectMapper.readTree(body).path("data");
    }

    /** A V1 settings body (no deriveFromDocuments). */
    private JsonNode settings(Boolean derive) throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("vatRegistered", true);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", true);
        if (derive != null) s.put("deriveFromDocuments", derive);
        return data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", s), 200);
    }

    private static Map<String, Object> line(String description, String reference, long unitPrice, int quantity) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        if (reference != null) line.put("reference", reference);
        return line;
    }

    private static Map<String, Object> draftBody(String clientName, String phone) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("name", clientName);
        client.put("phone", phone);
        client.put("email", "awa@example.com");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", client);
        body.put("lines", List.of(line("Riz 25kg", null, 15_000, 2), line("Huile", "6001234567890", 2_500, 3)));
        body.put("discountAmount", 1_000);
        return body;
    }

    private JsonNode issue(JsonNode draft) throws Exception {
        return data(call(HttpMethod.POST, "/api/v1/invoices/" + draft.path("id").asText() + "/issue", null), 200);
    }

    /** A catalogue product inserted as an existing one (manual creation, merge, import…). */
    private UUID product(String name, String key, int price, boolean manual, UUID mergedInto) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, price, price_manual, product_key, "
                + "merged_into_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now() - interval '1 hour', "
                + "now() - interval '1 hour')", id, name, "T-" + id.toString().substring(0, 8), price, manual, key,
                mergedInto);
        return id;
    }

    private int price(UUID product) {
        return jdbc.queryForObject("SELECT price FROM \"" + schema + "\".products WHERE id = ?", Integer.class,
                product);
    }

    private UUID store(String name, int secondsAfter) {
        UUID id = UUID.randomUUID();
        java.sql.Timestamp at = java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(secondsAfter));
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'STORE', true, ?, ?)", id, name, at, at);
        return id;
    }

    private String memberToken(UUID storeId, String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Paul', 'Ekane', false)", UUID.randomUUID(), userId,
                storeId);
        return TestAccess.token(jdbc, jwt, userId, schema, role);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\"." + table, Integer.class);
    }

    private static void sameTotals(JsonNode a, JsonNode b) {
        for (String field : List.of("totalHt", "totalVat", "totalTtc", "subtotal", "discountAmount")) {
            assertThat(a.path(field).asLong()).as(field).isEqualTo(b.path(field).asLong());
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DOCUMENT tenant, no explicit choice: new client and untracked products derived, totals unchanged; "
            + "a second invoice reuses them")
    void documentTenantDerives() throws Exception {
        saleFlow("DOCUMENT");
        assertThat(settings(null).path("deriveFromDocuments").asBoolean()).isTrue();
        assertThat(data(call(HttpMethod.GET, "/api/v1/invoicing/settings", null), 200)
                .path("deriveFromDocuments").asBoolean()).isTrue();
        int clientsBefore = count("clients");
        int productsBefore = count("products");

        JsonNode draft = data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699 00 00 01")), 201);
        assertThat(draft.path("client").path("id").isNull()).isTrue();
        JsonNode issued = issue(draft);

        sameTotals(issued, draft);
        String clientId = issued.path("client").path("id").asText();
        assertThat(clientId).isNotBlank();
        assertThat(issued.path("client").path("name").asText()).isEqualTo("Awa");
        Map<String, Object> client = jdbc.queryForMap("SELECT name, phone_normalized, store_id, email FROM \""
                + schema + "\".clients WHERE id = ?::uuid", clientId);
        assertThat(client).containsEntry("name", "Awa").containsEntry("phone_normalized", "+237699000001")
                .containsEntry("store_id", oldestStore).containsEntry("email", "awa@example.com");
        String rice = issued.path("lines").get(0).path("productId").asText();
        String oil = issued.path("lines").get(1).path("productId").asText();
        assertThat(rice).isNotBlank();
        assertThat(oil).isNotBlank().isNotEqualTo(rice);
        Map<String, Object> riceRow = jdbc.queryForMap("SELECT name, price, track_stock, price_manual, product_key, "
                + "barcode FROM \"" + schema + "\".products WHERE id = ?::uuid", rice);
        assertThat(riceRow).containsEntry("name", "Riz 25kg").containsEntry("price", 15_000)
                .containsEntry("track_stock", false).containsEntry("price_manual", false)
                .containsEntry("product_key", "nm:riz 25kg");
        assertThat(jdbc.queryForMap("SELECT product_key, barcode, price FROM \"" + schema
                + "\".products WHERE id = ?::uuid", oil)).containsEntry("product_key", "bc:6001234567890")
                .containsEntry("barcode", "6001234567890").containsEntry("price", 2_500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".invoice_items WHERE invoice_id = "
                + "?::uuid AND product_id IS NOT NULL", Integer.class, issued.path("id").asText())).isEqualTo(2);
        assertThat(count("clients")).isEqualTo(clientsBefore + 1);
        assertThat(count("products")).isEqualTo(productsBefore + 2);

        // second invoice, same phone (other form) and same lines, new price for the rice
        Map<String, Object> second = draftBody("Awa Ndiaye", "+237 6 99 00 00 01");
        second.put("lines", List.of(line("riz 25KG", null, 16_000, 1), line("Huile", "6001234567890", 2_500, 1)));
        JsonNode again = issue(data(call(HttpMethod.POST, "/api/v1/invoices", second), 201));
        assertThat(again.path("client").path("id").asText()).isEqualTo(clientId);
        assertThat(again.path("client").path("name").asText()).isEqualTo("Awa Ndiaye"); // the document keeps its text
        assertThat(again.path("lines").get(0).path("productId").asText()).isEqualTo(rice);
        assertThat(again.path("lines").get(1).path("productId").asText()).isEqualTo(oil);
        assertThat(count("clients")).isEqualTo(clientsBefore + 1);
        assertThat(count("products")).isEqualTo(productsBefore + 2);
        assertThat(jdbc.queryForObject("SELECT name FROM \"" + schema + "\".clients WHERE id = ?::uuid", String.class,
                clientId)).isEqualTo("Awa"); // never renamed
        assertThat(jdbc.queryForObject("SELECT price FROM \"" + schema + "\".products WHERE id = ?::uuid",
                Integer.class, rice)).isEqualTo(16_000); // last sale price (automatic price)
    }

    @Test
    @DisplayName("POS tenant (no choice), or an explicit « off »: nothing derived, the invoice is issued normally")
    void posTenantOrOff() throws Exception {
        assertThat(settings(null).path("deriveFromDocuments").asBoolean()).isFalse(); // POS by default
        int clientsBefore = count("clients");
        int productsBefore = count("products");
        JsonNode issued = issue(data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000002")), 201));
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.path("client").path("id").isNull()).isTrue();
        assertThat(issued.path("lines").get(0).path("productId").isNull()).isTrue();

        saleFlow("DOCUMENT");
        assertThat(settings(false).path("deriveFromDocuments").asBoolean()).isFalse();
        assertThat(settings(null).path("deriveFromDocuments").asBoolean()).isFalse(); // V1 body keeps the choice
        JsonNode off = issue(data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000002")), 201));
        assertThat(off.path("client").path("id").isNull()).isTrue();
        assertThat(count("clients")).isEqualTo(clientsBefore);
        assertThat(count("products")).isEqualTo(productsBefore);

        saleFlow("POS");
        assertThat(settings(true).path("deriveFromDocuments").asBoolean()).isTrue(); // explicit choice wins
    }

    @Test
    @DisplayName("catalog disabled: the client is derived, the products are not")
    void catalogDisabled() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        module("catalog", "DISABLED");
        int productsBefore = count("products");
        JsonNode issued = issue(data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000003")), 201));
        assertThat(issued.path("client").path("id").asText()).isNotBlank();
        assertThat(issued.path("lines").get(0).path("productId").isNull()).isTrue();
        assertThat(count("products")).isEqualTo(productsBefore);
    }

    @Test
    @DisplayName("crm disabled and a bad phone: products derived, no client, no error")
    void crmDisabledOrBadPhone() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        JsonNode badPhone = issue(data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "12")), 201));
        assertThat(badPhone.path("client").path("id").isNull()).isTrue();
        assertThat(badPhone.path("lines").get(0).path("productId").asText()).isNotBlank();

        module("crm", "DISABLED");
        int clientsBefore = count("clients");
        JsonNode issued = issue(data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000004")), 201));
        assertThat(issued.path("client").path("id").isNull()).isTrue();
        assertThat(count("clients")).isEqualTo(clientsBefore);
    }

    @Test
    @DisplayName("quote conversion: the converted invoice is linked (the quote itself never derives)")
    void quoteConversion() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        Map<String, Object> body = draftBody("Awa", "699000005");
        body.put("kind", "QUOTE");
        JsonNode quote = data(call(HttpMethod.POST, "/api/v1/invoices", body), 201);
        int clientsBefore = count("clients");
        assertThat(quote.path("client").path("id").isNull()).isTrue();

        JsonNode invoice = data(call(HttpMethod.POST, "/api/v1/invoices/" + quote.path("id").asText() + "/convert",
                Map.of()), 201);
        assertThat(invoice.path("kind").asText()).isEqualTo("INVOICE");
        assertThat(invoice.path("client").path("id").asText()).isNotBlank();
        assertThat(invoice.path("lines").get(1).path("productId").asText()).isNotBlank();
        sameTotals(invoice, quote);
        assertThat(count("clients")).isEqualTo(clientsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT client_id FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                UUID.class, quote.path("id").asText())).isNull();
    }

    @Test
    @DisplayName("existing products: by name only (manual « Huile », line « huile » + barcode), merged → merge target, "
            + "a line priced 0 never resets a price; nothing created")
    void existingProducts() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        UUID oil = product("Huile", null, 3_000, true, null);          // no key: matched by its unique name only
        UUID sugarTarget = product("Sucre en poudre", "nm:sucre en poudre", 900, false, null);
        UUID sugarMerged = product("Sucre", "nm:sucre", 800, false, sugarTarget); // merged into the target
        UUID salt = product("Sel", "nm:sel", 500, false, null);         // automatic price
        int productsBefore = count("products");

        Map<String, Object> body = draftBody("Awa", "699000006");
        body.put("lines", List.of(line("huile", "6001234567890", 2_500, 1), line("Sucre", null, 1_000, 1),
                line("Sel", null, 0, 2)));
        JsonNode draft = data(call(HttpMethod.POST, "/api/v1/invoices", body), 201);
        JsonNode issued = issue(draft);

        sameTotals(issued, draft);
        assertThat(issued.path("lines").get(0).path("productId").asText()).isEqualTo(oil.toString());
        assertThat(issued.path("lines").get(1).path("productId").asText()).isEqualTo(sugarTarget.toString())
                .isNotEqualTo(sugarMerged.toString());
        assertThat(issued.path("lines").get(2).path("productId").asText()).isEqualTo(salt.toString());
        assertThat(count("products")).isEqualTo(productsBefore);
        assertThat(price(oil)).isEqualTo(3_000);        // manual price kept
        assertThat(price(sugarTarget)).isEqualTo(1_000); // the target follows the last sale price
        assertThat(price(sugarMerged)).isEqualTo(800);   // the merged one is untouched
        assertThat(price(salt)).isEqualTo(500);          // a free line never resets a price
    }

    @Test
    @DisplayName("store member of the newer store issues over REST: the derived client belongs to the member's store")
    void memberStore() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        UUID storeB = store("Boutique B", 120);
        String member = memberToken(storeB, "EMPLOYEE");

        JsonNode draft = data(call(member, HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000007")), 201);
        JsonNode issued = data(call(member, HttpMethod.POST, "/api/v1/invoices/" + draft.path("id").asText()
                + "/issue", null), 200);

        String clientId = issued.path("client").path("id").asText();
        assertThat(clientId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT store_id FROM \"" + schema + "\".clients WHERE id = ?::uuid",
                UUID.class, clientId)).isEqualTo(storeB).isNotEqualTo(oldestStore);
    }

    @Test
    @DisplayName("an issue refused by the checks (due date before today, DOCUMENTS quota used up) derives nothing")
    void refusedIssueDerivesNothing() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        int clientsBefore = count("clients");
        int productsBefore = count("products");

        // a draft saved yesterday with yesterday as its due date
        JsonNode late = data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000008")), 201);
        jdbc.update("UPDATE \"" + schema + "\".invoices SET due_date = CURRENT_DATE - 2 WHERE id = ?::uuid",
                late.path("id").asText());
        MvcResult refused = call(HttpMethod.POST, "/api/v1/invoices/" + late.path("id").asText() + "/issue", null);
        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString(
                StandardCharsets.UTF_8)).isEqualTo(422); // VALIDATION_ERROR (due date)
        assertThat(count("clients")).isEqualTo(clientsBefore);
        assertThat(count("products")).isEqualTo(productsBefore);

        // the plan's DOCUMENTS quota used up (a DAY limit of 0 on the tenant's plan, restored afterwards)
        String plan = jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId);
        List<Integer> previous = jdbc.queryForList("SELECT quota_limit FROM public.plan_quotas WHERE plan_type = ? "
                + "AND metric = 'DOCUMENTS' AND period = 'DAY'", Integer.class, plan);
        jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, 'DOCUMENTS', "
                + "'DAY', 0) ON CONFLICT (plan_type, metric, period) DO UPDATE SET quota_limit = 0", plan);
        try {
            JsonNode draft = data(call(HttpMethod.POST, "/api/v1/invoices", draftBody("Awa", "699000009")), 201);
            MvcResult overQuota = call(HttpMethod.POST, "/api/v1/invoices/" + draft.path("id").asText() + "/issue",
                    null);
            String body = overQuota.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(overQuota.getResponse().getStatus()).as(body).isEqualTo(403);
            assertThat(objectMapper.readTree(body).path("domainCode").asText()).isEqualTo("QUOTA_EXCEEDED");
            assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".invoices WHERE id = ?::uuid",
                    String.class, draft.path("id").asText())).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".invoice_items WHERE "
                    + "invoice_id = ?::uuid AND product_id IS NOT NULL", Integer.class, draft.path("id").asText()))
                    .isZero();
        } finally {
            if (previous.isEmpty()) {
                jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ? AND metric = 'DOCUMENTS' "
                        + "AND period = 'DAY'", plan);
            } else {
                jdbc.update("UPDATE public.plan_quotas SET quota_limit = ? WHERE plan_type = ? AND metric = "
                        + "'DOCUMENTS' AND period = 'DAY'", previous.get(0), plan);
            }
        }
        assertThat(count("clients")).isEqualTo(clientsBefore);
        assertThat(count("products")).isEqualTo(productsBefore);
    }

    @Test
    @DisplayName("20.14: an owner's invoice made in a newer store, issued with derivation → the client is created in "
            + "that store (not the oldest), the invoice keeps it")
    void derivedClientFollowsTheInvoiceStore() throws Exception {
        saleFlow("DOCUMENT");
        settings(null);
        UUID newer = store("Boutique Bonapriso", 60);
        Map<String, Object> body = draftBody("Awa", "699 00 00 07");
        body.put("storeId", newer.toString());

        JsonNode draft = data(call(HttpMethod.POST, "/api/v1/invoices", body), 201);
        assertThat(draft.path("storeId").asText()).isEqualTo(newer.toString());
        JsonNode issued = issue(draft);

        assertThat(issued.path("storeId").asText()).isEqualTo(newer.toString());
        String clientId = issued.path("client").path("id").asText();
        assertThat(clientId).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT store_id FROM \"" + schema + "\".clients WHERE id = ?::uuid",
                UUID.class, clientId)).isEqualTo(newer).isNotEqualTo(oldestStore);
    }
}
