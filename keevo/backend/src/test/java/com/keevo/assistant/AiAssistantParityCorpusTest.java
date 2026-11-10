package com.keevo.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.assistant.chat.adapter.out.model.ScriptedAssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.4 — the parity corpus of {@code keevo/contracts/ai-assistant.json} (≥ 30 real questions of Bref Point's
 * guided topics) replayed through {@code POST /api/v1/ai/chat} with the scripted model on the fixture the contract
 * describes: for each question the expected tools are called (audit, channel ASSISTANT, outcome OK), every expected
 * figure appears exactly in the reply, and no reply carries an identifier.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AI assistant — parity corpus: tool calls and exact figures (21.4)")
class AiAssistantParityCorpusTest extends FullContextIntegrationTest {

    static final Path CONTRACT = Path.of("..", "contracts", "ai-assistant.json");
    static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final UUID RICE = UUID.randomUUID();
    static final UUID OIL = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AssistantModel assistantModel;

    private String schema;
    private String ownerToken;

    @AfterEach
    void tearDown() {
        ((ScriptedAssistantModel) assistantModel).reset();
        if (schema != null) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", schema);
            moduleRegistry.evict(schema);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("every corpus question: expected tools called, exact figures in the reply, no identifier")
    void corpus() throws Exception {
        JsonNode contract = objectMapper.readTree(Files.readString(CONTRACT));
        JsonNode corpus = contract.path("corpus");
        assertThat(corpus.size()).as("parity corpus size").isGreaterThanOrEqualTo(30);
        fixture();
        String today = LocalDate.now(KeevoZones.WAT).toString();
        List<String> failures = new ArrayList<>();
        for (JsonNode q : corpus) {
            ObjectNode rule = ((ObjectNode) q.deepCopy());
            String json = objectMapper.writeValueAsString(rule).replace("@today", today);
            ObjectNode script = objectMapper.createObjectNode();
            ((ArrayNode) script.putArray("rules")).add(objectMapper.readTree(json));
            script.put("fallback", "question non prévue");
            ((ScriptedAssistantModel) assistantModel).useScript(script);
            int calls = assistantCalls().size();

            MvcResult r = http(HttpMethod.POST, "/api/v1/ai/chat", Map.of("message", q.path("question").asText()));
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String id = q.path("id").asText();
            if (r.getResponse().getStatus() != 200) {
                failures.add(id + ": HTTP " + r.getResponse().getStatus() + " " + body);
                continue;
            }
            String reply = objectMapper.readTree(body).path("data").path("reply").asText();
            for (JsonNode expected : q.path("expect")) {
                if (!reply.contains(expected.asText())) {
                    failures.add(id + ": « " + expected.asText() + " » missing from « " + reply + " »");
                }
            }
            if (UUID_TEXT.matcher(reply).find()) {
                failures.add(id + ": identifier in « " + reply + " »");
            }
            List<String> expectedCalls = new ArrayList<>();
            q.path("steps").forEach(s -> expectedCalls.add(s.path("tool").asText() + ":OK"));
            List<String> made = assistantCalls();
            List<String> these = made.subList(calls, made.size());
            if (!these.equals(expectedCalls)) {
                failures.add(id + ": tools " + these + " instead of " + expectedCalls);
            }
        }
        assertThat(failures).as("parity corpus").isEmpty();
    }

    // ── fixture (described in the contract) ─────────────────────────────────────────────────────────────

    private void fixture() throws Exception {
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        String q = "\"" + schema + "\".";
        UUID akwa = jdbc.queryForObject("SELECT id FROM " + q + "stores ORDER BY created_at LIMIT 1", UUID.class);
        jdbc.update("UPDATE " + q + "stores SET name = 'Boutique Akwa' WHERE id = ?", akwa);
        UUID bonapriso = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "stores (id, created_at, updated_at, is_active, name, type) VALUES (?, now() "
                + "+ interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", bonapriso);
        UUID owner = UUID.randomUUID();
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, owner, schema, "OWNER");
        UUID awa = employee(q, akwa, "Awa", "Ngono");
        UUID jean = employee(q, akwa, "Jean", "Mballa");
        UUID marie = employee(q, bonapriso, "Marie", "Fotso");
        jdbc.update("INSERT INTO " + q + "products (id, name, sku, status, price, buy_price) VALUES "
                + "(?, 'Riz parfumé 25 kg', 'KEV-RIZ025', 'ACTIVE', 10000, 7000), "
                + "(?, 'Huile de palme 5 L', 'KEV-HUI005', 'ACTIVE', 5000, 3500)", RICE, OIL);
        sale(q, "2026-09-10", akwa, awa, RICE, 3);
        sale(q, "2026-09-15", bonapriso, marie, OIL, 4);
        sale(q, "2026-10-05", akwa, awa, RICE, 2);
        sale(q, "2026-10-12", akwa, awa, OIL, 4);
        sale(q, "2026-10-20", akwa, jean, RICE, 5);
        sale(q, "2026-10-08", bonapriso, marie, RICE, 5);
        sale(q, "2026-10-25", bonapriso, marie, OIL, 2);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(http(HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
        String today = LocalDate.now(KeevoZones.WAT).toString();
        String inv1 = invoice("Awa Ngono", "+237690000001", 3);
        String inv2 = invoice("Jean Mballa", "+237690000002", 5);
        String inv3 = invoice("Marie Fotso", "+237690000003", 2);
        data(http(HttpMethod.POST, "/api/v1/invoices/" + inv1 + "/payments",
                Map.of("amount", 10_000, "paidAt", today, "method", "Espèces")), 201);
        data(http(HttpMethod.POST, "/api/v1/invoices/" + inv3 + "/payments",
                Map.of("amount", 20_000, "paidAt", today, "method", "Mobile Money")), 201);
        jdbc.update("UPDATE " + q + "invoices SET due_date = DATE '2026-10-01', payment_state = 'OVERDUE' WHERE id = ?",
                UUID.fromString(inv2));

        data(http(HttpMethod.POST, "/api/v1/expenses", Map.of("amount", 150_000, "category", "rent", "expenseDate",
                "2026-10-01", "storeId", akwa.toString(), "label", "Loyer d'octobre")), 201);
        data(http(HttpMethod.POST, "/api/v1/expenses", Map.of("amount", 25_000, "category", "transport",
                "expenseDate", "2026-10-15", "storeId", bonapriso.toString(), "label", "Livraison")), 201);
    }

    private UUID employee(String q, UUID store, String first, String last) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "employees (user_id, store_id, first_name, last_name, password_change_required) "
                + "VALUES (?, ?, ?, ?, false)", user, store, first, last);
        return user;
    }

    private void sale(String q, String day, UUID store, UUID seller, UUID product, int quantity) {
        boolean rice = product.equals(RICE);
        int price = rice ? 10_000 : 5_000;
        Timestamp noon = Timestamp.from(LocalDate.parse(day).atTime(12, 0).atZone(KeevoZones.WAT).toInstant());
        UUID sale = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "sales (id, created_at, employee_id, occurred_at, payment_mode, status, "
                + "store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                sale, noon, seller, noon, store, price * quantity);
        jdbc.update("INSERT INTO " + q + "sale_items (sale_id, product_id, product_name, applied_unit_price, quantity, "
                + "subtotal, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", sale, product,
                rice ? "Riz parfumé 25 kg" : "Huile de palme 5 L", price, quantity, price * quantity, noon);
    }

    private String invoice(String client, String phone, int quantity) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", quantity);
        line.put("unitPrice", 10_000);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", client, "phone", phone));
        body.put("lines", List.of(line));
        String id = data(http(HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
        data(http(HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    private MvcResult http(HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + ownerToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return objectMapper.readTree(text).path("data");
    }

    private List<String> assistantCalls() {
        return jdbc.queryForList("SELECT c.tool || ':' || c.outcome FROM public.ai_tool_calls c JOIN public.tenants t "
                + "ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.channel = 'ASSISTANT' ORDER BY c.called_at, "
                + "c.id", String.class, schema);
    }
}
