package com.keevo.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.4 / 21.4.2 — the company the parity corpus of {@code keevo/contracts/ai-assistant.json} describes (two
 * stores, two products, three sellers, seven sales, three invoices, two expenses), built through the real APIs where
 * they exist; shared by the scripted corpus test and the real-model test.
 */
final class AssistantCorpusFixture {

    final UUID rice = UUID.randomUUID();
    final UUID oil = UUID.randomUUID();
    String schema;
    String ownerToken;
    /** Awa Ngono, employee of Boutique Akwa (sold, never invoiced). */
    String employeeToken;
    UUID akwa;
    UUID bonapriso;

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwt;
    private final TenantFactory tenantFactory;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    AssistantCorpusFixture(MockMvc mockMvc, ObjectMapper objectMapper, JwtTokenProvider jwt, TenantFactory tenantFactory,
                           TransactionTemplate tx, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jwt = jwt;
        this.tenantFactory = tenantFactory;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    AssistantCorpusFixture create() throws Exception {
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        String q = "\"" + schema + "\".";
        akwa = jdbc.queryForObject("SELECT id FROM " + q + "stores ORDER BY created_at LIMIT 1", UUID.class);
        jdbc.update("UPDATE " + q + "stores SET name = 'Boutique Akwa' WHERE id = ?", akwa);
        bonapriso = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "stores (id, created_at, updated_at, is_active, name, type) VALUES (?, now() "
                + "+ interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", bonapriso);
        UUID owner = UUID.randomUUID();
        ownerToken = TestAccess.token(jdbc, jwt, owner, schema, "OWNER");
        UUID awa = employee(q, akwa, "Awa", "Ngono");
        UUID jean = employee(q, akwa, "Jean", "Mballa");
        UUID marie = employee(q, bonapriso, "Marie", "Fotso");
        employeeToken = TestAccess.token(jdbc, jwt, awa, schema, "EMPLOYEE", "ACTIVE", akwa, false);
        jdbc.update("INSERT INTO " + q + "products (id, name, sku, status, price, buy_price) VALUES "
                + "(?, 'Riz parfumé 25 kg', 'KEV-RIZ025', 'ACTIVE', 10000, 7000), "
                + "(?, 'Huile de palme 5 L', 'KEV-HUI005', 'ACTIVE', 5000, 3500)", rice, oil);
        sale(q, "2026-09-10", akwa, awa, rice, 3);
        sale(q, "2026-09-15", bonapriso, marie, oil, 4);
        sale(q, "2026-10-05", akwa, awa, rice, 2);
        sale(q, "2026-10-12", akwa, awa, oil, 4);
        sale(q, "2026-10-20", akwa, jean, rice, 5);
        sale(q, "2026-10-08", bonapriso, marie, rice, 5);
        sale(q, "2026-10-25", bonapriso, marie, oil, 2);

        settings(ownerToken);
        String today = LocalDate.now(KeevoZones.WAT).toString();
        String inv1 = invoice(ownerToken, "Awa Ngono", "+237690000001", 3);
        String inv2 = invoice(ownerToken, "Jean Mballa", "+237690000002", 5);
        String inv3 = invoice(ownerToken, "Marie Fotso", "+237690000003", 2);
        data(http(ownerToken, HttpMethod.POST, "/api/v1/invoices/" + inv1 + "/payments",
                Map.of("amount", 10_000, "paidAt", today, "method", "Espèces")), 201);
        data(http(ownerToken, HttpMethod.POST, "/api/v1/invoices/" + inv3 + "/payments",
                Map.of("amount", 20_000, "paidAt", today, "method", "Mobile Money")), 201);
        jdbc.update("UPDATE " + q + "invoices SET due_date = DATE '2026-10-01', payment_state = 'OVERDUE' WHERE id = ?",
                UUID.fromString(inv2));

        data(http(ownerToken, HttpMethod.POST, "/api/v1/expenses", Map.of("amount", 150_000, "category", "rent",
                "expenseDate", "2026-10-01", "storeId", akwa.toString(), "label", "Loyer d'octobre")), 201);
        data(http(ownerToken, HttpMethod.POST, "/api/v1/expenses", Map.of("amount", 25_000, "category", "transport",
                "expenseDate", "2026-10-15", "storeId", bonapriso.toString(), "label", "Livraison")), 201);
        return this;
    }

    UUID employee(String q, UUID store, String first, String last) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "employees (user_id, store_id, first_name, last_name, password_change_required) "
                + "VALUES (?, ?, ?, ?, false)", user, store, first, last);
        return user;
    }

    void sale(String q, String day, UUID store, UUID seller, UUID product, int quantity) {
        boolean isRice = product.equals(rice);
        int price = isRice ? 10_000 : 5_000;
        Timestamp noon = Timestamp.from(LocalDate.parse(day).atTime(12, 0).atZone(KeevoZones.WAT).toInstant());
        UUID sale = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "sales (id, created_at, employee_id, occurred_at, payment_mode, status, "
                + "store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                sale, noon, seller, noon, store, price * quantity);
        jdbc.update("INSERT INTO " + q + "sale_items (sale_id, product_id, product_name, applied_unit_price, quantity, "
                + "subtotal, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", sale, product,
                isRice ? "Riz parfumé 25 kg" : "Huile de palme 5 L", price, quantity, price * quantity, noon);
    }

    void settings(String token) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(http(token, HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
    }

    /** An issued invoice of {@code quantity} × 10 000 FCFA. */
    String invoice(String token, String client, String phone, int quantity) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", quantity);
        line.put("unitPrice", 10_000);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", client, "phone", phone));
        body.put("lines", List.of(line));
        String id = data(http(token, HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
        data(http(token, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
        return id;
    }

    MvcResult http(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    JsonNode data(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return objectMapper.readTree(text).path("data");
    }

    /** Tool calls of the assistant channel audited for {@code tenant}, in order ({@code tool:OUTCOME}). */
    List<String> assistantCalls(String tenant) {
        return jdbc.queryForList("SELECT c.tool || ':' || c.outcome FROM public.ai_tool_calls c JOIN public.tenants t "
                + "ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.channel = 'ASSISTANT' ORDER BY c.called_at, "
                + "c.id", String.class, tenant);
    }

    /** AI credits counted today for {@code tenant}. */
    long aiUsedToday(String tenant) {
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(c.used), 0) FROM public.usage_counters c JOIN "
                + "public.tenants t ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.metric = 'AI_CREDITS' AND "
                + "c.period = 'DAY'", Long.class, tenant);
        return used == null ? 0 : used;
    }
}
