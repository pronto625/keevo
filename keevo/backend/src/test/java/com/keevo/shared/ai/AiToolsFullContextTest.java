package com.keevo.shared.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.finance.summary.adapter.in.mcp.FinanceMcpTools;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.ai.AiToolResult.Outcome;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 21.8 — the AI tool layer end to end on PostgreSQL with the production beans (proxies, method security,
 * entitlement aspect, module provisioning): tool set at boot, listing per role, document visibility of 20.14 (employee
 * → own, manager → store, owner → all, store filter owner-only), sales of a store member, client store scope, finance
 * refused to an employee (registry and bean proxy), module disabled, tenant isolation, no technical id in results,
 * one audit row per call without arguments.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AI tools — scope, gates, isolation and audit on the full context (Story 21.8)")
class AiToolsFullContextTest extends FullContextIntegrationTest {

    static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AiToolRegistry tools;
    @Autowired FinanceMcpTools financeBean;
    @Autowired com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository preferences;
    @Autowired com.keevo.shared.ai.internal.AiToolCallAudit callAudit;

    record Member(UUID user, String role, UUID store, String token) {
    }

    private final List<String> schemas = new ArrayList<>();
    private String schema;
    private UUID storeA;
    private UUID storeB;
    private Member owner;
    private Member employee1;
    private Member employee2;
    private Member managerB;

    @BeforeEach
    void setUp() throws Exception {
        schema = newTenant();
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        owner = member(schema, "OWNER", null);
        employee1 = member(schema, "EMPLOYEE", storeA);
        employee2 = member(schema, "EMPLOYEE", storeA);
        managerB = member(schema, "MANAGER", storeB);
        settings(owner);
        issue(employee1, create(employee1, "Client E1", "+237690000001"));
        issue(employee2, create(employee2, "Client E2", "+237690000002"));
        issue(managerB, create(managerB, "Client MB", "+237690000003"));
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
        return s;
    }

    private Member member(String tenant, String role, UUID store) {
        UUID user = UUID.randomUUID();
        if (store != null) {
            jdbc.update("INSERT INTO \"" + tenant + "\".employees (user_id, store_id, first_name, last_name, "
                    + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", user, store);
            return new Member(user, role, store,
                    TestAccess.token(jdbc, jwtTokenProvider, user, tenant, role, "ACTIVE", store, false));
        }
        return new Member(user, role, null, TestAccess.token(jdbc, jwtTokenProvider, user, tenant, role));
    }

    private MvcResult http(Member m, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + m.token());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return objectMapper.readTree(body).path("data");
    }

    private void settings(Member m) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("legalName", "Boutique Awa SARL");
        settings.put("address", "Rue Joss, Akwa, Douala");
        settings.put("vatRegistered", false);
        settings.put("vatRate", 19.25);
        settings.put("pricesIncludeVat", true);
        data(http(m, HttpMethod.PUT, "/api/v1/invoicing/settings", settings), 200);
    }

    private String create(Member m, String client, String phone) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", "Sac de riz 25 kg");
        line.put("quantity", 1);
        line.put("unitPrice", 10_000);
        line.put("vatTreatment", "STANDARD");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client", Map.of("name", client, "phone", phone));
        body.put("lines", List.of(line));
        return data(http(m, HttpMethod.POST, "/api/v1/invoices", body), 201).path("id").asText();
    }

    private void issue(Member m, String id) throws Exception {
        data(http(m, HttpMethod.POST, "/api/v1/invoices/" + id + "/issue", null), 200);
    }

    /** The session as the JWT filter builds it (principal, role authority, store details) + the tenant. */
    private void as(String tenant, Member m) {
        var auth = new UsernamePasswordAuthenticationToken(m.user(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + m.role())));
        auth.setDetails(new AuthDetails("Paul", m.store(), null));
        SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.setCurrentTenant(tenant);
    }

    private JsonNode ok(String tool, String args) throws Exception {
        AiToolResult r = tools.call(tool, args, AiToolChannel.INTERNAL);
        assertThat(r.outcome()).as(r.message()).isEqualTo(Outcome.OK);
        return objectMapper.readTree(r.json());
    }

    private static List<String> clients(JsonNode unpaid) {
        List<String> names = new ArrayList<>();
        unpaid.path("invoices").forEach(n -> names.add(n.path("client").asText()));
        return names;
    }

    private List<String> allowed() {
        return tools.allowedTools().stream().map(AiToolDescriptor::name).toList();
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("boot: 16 tools with a French description and a schema; listing follows the role")
    void toolSetAndListing() {
        assertThat(tools.allTools()).extracting(AiToolDescriptor::name).containsExactly(
                "activity_overview", "compare_revenue_periods", "financial_result", "invoice_collections",
                "list_expenses", "list_stores", "low_stock_products", "revenue_summary", "sales_history",
                "search_clients", "search_products", "seller_ranking", "stock_by_store", "store_performance",
                "unpaid_call_list", "unpaid_invoices");
        assertThat(tools.allTools()).allSatisfy(t -> {
            assertThat(t.description()).isNotBlank();
            assertThat(t.inputSchema()).startsWith("{");
        });

        as(schema, owner);
        assertThat(allowed()).contains("unpaid_invoices", "financial_result", "list_stores", "search_clients");
        as(schema, employee1);
        assertThat(allowed()).contains("unpaid_invoices", "sales_history", "search_clients", "search_products")
                .doesNotContain("financial_result", "list_stores", "activity_overview", "list_expenses",
                        "revenue_summary");
        as(schema, managerB);
        assertThat(allowed()).contains("unpaid_invoices", "list_expenses").doesNotContain("financial_result");
    }

    @Test
    @DisplayName("unpaid documents: employee → own, manager → store, owner → all or one store; filter owner-only")
    void documentVisibility() throws Exception {
        as(schema, employee1);
        assertThat(clients(ok("unpaid_invoices", "{}"))).containsExactly("Client E1");
        AiToolResult filtered = tools.call("unpaid_invoices", "{\"storeId\":\"" + storeB + "\"}",
                AiToolChannel.INTERNAL);
        assertThat(filtered.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(filtered.errorCode()).isEqualTo("FORBIDDEN");

        as(schema, managerB);
        assertThat(clients(ok("unpaid_invoices", null))).containsExactly("Client MB");

        as(schema, owner);
        JsonNode all = ok("unpaid_invoices", "{}");
        assertThat(clients(all)).containsExactlyInAnyOrder("Client E1", "Client E2", "Client MB");
        assertThat(all.path("totalInvoices").asLong()).isEqualTo(3);
        assertThat(all.path("invoices").get(0).path("due").asLong()).isEqualTo(10_000);
        assertThat(all.path("invoices").get(0).path("state").asText()).isNotBlank().doesNotContain("UNPAID");
        assertThat(UUID_TEXT.matcher(all.toString()).find()).as("no technical id").isFalse();
        assertThat(clients(ok("unpaid_invoices", "{\"storeId\":\"" + storeB + "\"}"))).containsExactly("Client MB");
    }

    @Test
    @DisplayName("call list and collections: a partial payment lowers the due and counts as collected")
    void callListAndCollections() throws Exception {
        as(schema, owner);
        String x1 = jdbc.queryForObject("SELECT CAST(id AS text) FROM \"" + schema + "\".invoices WHERE client_name = "
                + "'Client E1'", String.class);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("amount", 4_000);
        payment.put("paidAt", LocalDate.now(KeevoZones.WAT).toString());
        payment.put("method", "Mobile Money");
        data(http(owner, HttpMethod.POST, "/api/v1/invoices/" + x1 + "/payments", payment), 201);

        as(schema, owner);
        JsonNode calls = ok("unpaid_call_list", "{}");
        assertThat(calls.path("unpaidInvoices").asLong()).isEqualTo(3);
        assertThat(calls.path("clientsOwing").asLong()).isEqualTo(3);
        assertThat(calls.path("totalDue").asLong()).isEqualTo(26_000);
        assertThat(calls.path("clients")).hasSize(3);
        assertThat(ok("unpaid_call_list", "{\"limit\":1}").path("clients")).hasSize(1);
        assertThat(calls.path("clients").get(2).path("client").asText()).isEqualTo("Client E1");
        assertThat(calls.path("clients").get(2).path("due").asLong()).isEqualTo(6_000);
        assertThat(calls.path("clients").get(2).path("phone").asText()).isEqualTo("+237690000001");

        JsonNode collected = ok("invoice_collections", "{}");
        assertThat(collected.path("amount").asLong()).isEqualTo(4_000);
        assertThat(collected.path("byMethod").get(0).path("method").asText()).isEqualTo("Mobile Money");
        // the owner's store filter and the start of the period are applied
        assertThat(ok("invoice_collections", "{\"storeId\":\"" + storeB + "\"}").path("amount").asLong()).isZero();
        String tomorrow = LocalDate.now(KeevoZones.WAT).plusDays(1).toString();
        assertThat(ok("invoice_collections", "{\"from\":\"" + tomorrow + "\",\"to\":\"" + tomorrow + "\"}")
                .path("amount").asLong()).isZero();
        assertThat(tools.call("invoice_collections", "{\"from\":\"1900-01-01\"}", AiToolChannel.INTERNAL)
                .errorCode()).isEqualTo("VALIDATION_ERROR");

        // fully paid → out of the unpaid list; overdue only → just the late one
        String x2 = jdbc.queryForObject("SELECT CAST(id AS text) FROM \"" + schema + "\".invoices WHERE client_name = "
                + "'Client E2'", String.class);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        Map<String, Object> full = new LinkedHashMap<>(payment);
        full.put("amount", 10_000);
        data(http(owner, HttpMethod.POST, "/api/v1/invoices/" + x2 + "/payments", full), 201);
        jdbc.update("UPDATE \"" + schema + "\".invoices SET due_date = issue_date, payment_state = 'OVERDUE' "
                + "WHERE client_name = 'Client MB'");
        as(schema, owner);
        JsonNode unpaid = ok("unpaid_invoices", "{}");
        assertThat(clients(unpaid)).containsExactlyInAnyOrder("Client E1", "Client MB");
        assertThat(unpaid.path("totalInvoices").asLong()).isEqualTo(2);
        assertThat(clients(ok("unpaid_invoices", "{\"overdueOnly\":true}"))).containsExactly("Client MB");
        JsonNode after = ok("unpaid_call_list", "{}");
        assertThat(after.path("totalDue").asLong()).isEqualTo(16_000);
        assertThat(after.path("clients").get(0).path("client").asText()).isEqualTo("Client MB");
        assertThat(after.path("clients").get(0).path("overdueInvoices").asInt()).isEqualTo(1);
        as(schema, employee2); // their own document only: the full payment of Client E2
        assertThat(ok("invoice_collections", "{}").path("amount").asLong()).isEqualTo(10_000);
        as(schema, employee1);
        assertThat(ok("invoice_collections", "{}").path("amount").asLong()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("sales: a store member sees only their own sales, the owner every seller's of the store")
    void salesOfAStoreMember() throws Exception {
        Instant noon = LocalDate.now(KeevoZones.WAT).atTime(12, 0).atZone(KeevoZones.WAT).toInstant();
        for (Member seller : List.of(employee1, employee2)) {
            jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                            + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, "
                            + "5000, 0)", UUID.randomUUID(), Timestamp.from(noon), seller.user(), Timestamp.from(noon),
                    storeA);
        }
        as(schema, employee1);
        JsonNode own = ok("sales_history", "{}");
        assertThat(own.path("totalSales").asLong()).isEqualTo(1);
        assertThat(own.path("ownSalesOnly").asBoolean()).isTrue();
        assertThat(own.path("sales").get(0).path("payment").asText()).isEqualTo("Espèces");

        as(schema, owner);
        assertThat(tools.call("sales_history", "{}", AiToolChannel.INTERNAL).errorCode())
                .isEqualTo("VALIDATION_ERROR"); // the owner names the store
        assertThat(ok("sales_history", "{\"storeId\":\"" + storeA + "\"}").path("totalSales").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("seller ranking: refused to an employee until shared, then own store with colleagues anonymised")
    void sellerRanking() throws Exception {
        Instant noon = LocalDate.now(KeevoZones.WAT).atTime(12, 0).atZone(KeevoZones.WAT).toInstant();
        for (Member seller : List.of(employee1, employee2, managerB)) {
            jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                            + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, "
                            + "5000, 0)", UUID.randomUUID(), Timestamp.from(noon), seller.user(), Timestamp.from(noon),
                    seller.store());
        }
        as(schema, employee1);
        AiToolResult refused = tools.call("seller_ranking", "{}", AiToolChannel.ASSISTANT);
        assertThat(refused.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(refused.message()).isEqualTo("Le classement n'est pas partagé avec les employés.");

        TenantContext.setCurrentTenant(schema); // onboarding preferences, as LeaderboardEmployeeFullContextTest
        try {
            preferences.save(new com.keevo.identity.onboarding.domain.model.TenantPreferences(UUID.randomUUID(),
                    com.keevo.identity.onboarding.domain.model.SectorType.FOOD_GROCERY, "20:00:00", true,
                    Instant.now(), true, com.keevo.identity.api.ReportChannel.WHATSAPP, true, 0, "20:00:00",
                    com.keevo.identity.api.ReportChannel.WHATSAPP, true, com.keevo.identity.api.ReportChannel.WHATSAPP,
                    com.keevo.identity.api.StockAlertChannel.PUSH, true));
        } finally {
            TenantContext.clear();
        }
        MvcResult shared = http(owner, HttpMethod.PUT, "/api/v1/tenant/leaderboard-visibility",
                Map.of("visibleToEmployees", true));
        assertThat(shared.getResponse().getStatus()).as(shared.getResponse().getContentAsString()).isLessThan(300);
        as(schema, employee1);
        JsonNode ranking = ok("seller_ranking", "{\"storeId\":\"" + storeB + "\"}"); // store filter ignored
        List<String> names = new ArrayList<>();
        ranking.path("sellers").forEach(n -> names.add(n.path("seller").asText() + (n.path("me").asBoolean() ? "*"
                : "")));
        assertThat(names).hasSizeGreaterThanOrEqualTo(2)
                .anySatisfy(n -> assertThat(n).startsWith("Paul").endsWith("*"))
                .anySatisfy(n -> assertThat(n).startsWith("Vendeur "))
                .noneSatisfy(n -> assertThat(n).startsWith("Paul").doesNotEndWith("*"));
        assertThat(UUID_TEXT.matcher(ranking.toString()).find()).isFalse();

        as(schema, owner);
        JsonNode all = ok("seller_ranking", "{}");
        assertThat(all.path("sellers")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(all.path("sellers").findValuesAsText("seller")).noneMatch(n -> n.startsWith("Vendeur "));
    }

    @Test
    @DisplayName("stock: a member reads only their store; the owner any store")
    void stockScope() throws Exception {
        as(schema, employee1);
        AiToolResult other = tools.call("low_stock_products", "{\"storeId\":\"" + storeB + "\"}",
                AiToolChannel.INTERNAL);
        if (other.errorCode() != null && other.errorCode().startsWith("MODULE_")) {
            return; // multi-store not in this plan: nothing more to check here
        }
        assertThat(other.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(other.message()).isEqualTo("Vous ne pouvez consulter que le stock de votre boutique.");
        assertThat(ok("low_stock_products", "{}").path("totalProducts").asLong()).isGreaterThanOrEqualTo(0);
        JsonNode mine = ok("stock_by_store", "{}");
        assertThat(mine.findValuesAsText("storeId")).allSatisfy(id -> assertThat(id).isEqualTo(storeA.toString()));
        as(schema, owner);
        assertThat(ok("low_stock_products", "{\"storeId\":\"" + storeB + "\"}").path("totalProducts").asLong())
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("21.4.2: one debtor whatever the phone's format; a legacy invoice without state still owes")
    void callListKeys() throws Exception {
        issue(owner, create(owner, "Client Dup", "+237 690 000 077"));
        issue(owner, create(owner, "Client Dup", "690000077"));
        issue(owner, create(owner, "Client Ancien", "+237690000078"));
        jdbc.update("UPDATE \"" + schema + "\".invoices SET payment_state = NULL WHERE client_name = 'Client Ancien'");
        as(schema, owner);
        JsonNode calls = ok("unpaid_call_list", "{}");
        List<String> names = new ArrayList<>();
        calls.path("clients").forEach(c -> names.add(c.path("client").asText()));
        assertThat(names).containsOnlyOnce("Client Dup").contains("Client Ancien");
        calls.path("clients").forEach(c -> {
            if ("Client Dup".equals(c.path("client").asText())) {
                assertThat(c.path("invoices").asInt()).isEqualTo(2);
                assertThat(c.path("due").asLong()).isEqualTo(20_000);
            }
        });
    }

    @Test
    @DisplayName("21.4.2: financial_result named for a model (French granularity); activity_overview and list_expenses "
            + "answer; unknown criteria, too long periods and NUL searches are refused")
    void toolOutputsAndArguments() throws Exception {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        data(http(owner, HttpMethod.POST, "/api/v1/expenses", Map.of("amount", 15_000, "category", "rent",
                "expenseDate", LocalDate.now(KeevoZones.WAT).toString(), "storeId", storeA.toString(),
                "label", "Loyer")), 201);
        as(schema, owner);
        JsonNode finance = ok("financial_result", "{}");
        assertThat(finance.path("revenue").has("salesAndInvoicesCount")).isTrue();
        assertThat(finance.path("revenue").has("fullyPaidDocumentsTotal")).isTrue();
        assertThat(finance.path("revenue").has("notFullyPaidDocumentsTotal")).isTrue();
        assertThat(finance.path("revenue").has("invoiceCount")).isFalse();
        assertThat(finance.path("period").path("granularity").asText()).isIn("jour", "mois");
        assertThat(ok("activity_overview", "{}").has("revenueToday")).isTrue();
        JsonNode expenses = ok("list_expenses", "{}");
        assertThat(expenses.path("matching").asLong()).isEqualTo(1);
        assertThat(expenses.path("totalAmount").asLong()).isEqualTo(15_000);
        assertThat(expenses.path("expenses").get(0).path("category").asText()).isNotEqualTo("rent");
        assertThat(tools.call("list_expenses", "{\"sort\":\"biggest\"}", AiToolChannel.INTERNAL).errorCode())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(tools.call("store_performance", "{\"metric\":\"revenue\"}", AiToolChannel.INTERNAL).errorCode())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(tools.call("revenue_summary", "{\"from\":\"2000-01-01\",\"to\":\"2026-01-01\"}",
                AiToolChannel.INTERNAL).errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(tools.call("search_clients", "{\"search\":\"a\\u0000b\"}", AiToolChannel.INTERNAL).errorCode())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("21.4.2: a product is found by part of its barcode")
    void productByBarcode() throws Exception {
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, barcode, status, price, buy_price) "
                + "VALUES (?, 'Lait concentré', 'KEV-LAI001', '6201234567890', 'ACTIVE', 800, 500)", UUID.randomUUID());
        as(schema, owner);
        JsonNode found = ok("search_products", "{\"search\":\"234567\"}");
        assertThat(found.path("products")).hasSize(1);
        assertThat(found.path("products").get(0).path("name").asText()).isEqualTo("Lait concentré");
    }

    @Test
    @DisplayName("products: bounded SQL search by name / reference, literal wildcards, purchase price owner-only")
    void productSearch() throws Exception {
        for (String[] p : new String[][]{{"Riz parfumé 25 kg", "KEV-RIZ025"}, {"Huile 1 L", "KEV-HUI001"},
                {"Savon 100% karité", "KEV-SAV100"}}) {
            jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status, price, buy_price) "
                    + "VALUES (?, ?, ?, 'ACTIVE', 1000, 600)", UUID.randomUUID(), p[0], p[1]);
        }
        as(schema, owner);
        JsonNode riz = ok("search_products", "{\"search\":\"riz\"}");
        assertThat(riz.path("products")).hasSize(1);
        assertThat(riz.path("products").get(0).path("purchasePrice").asInt()).isEqualTo(600);
        assertThat(ok("search_products", "{\"search\":\"hui001\"}").path("products").get(0).path("name").asText())
                .isEqualTo("Huile 1 L");
        assertThat(ok("search_products", "{\"search\":\"100%\"}").path("products")).hasSize(1);
        assertThat(ok("search_products", "{\"search\":\"_\"}").path("products")).isEmpty();
        JsonNode first = ok("search_products", "{\"limit\":2}");
        assertThat(first.path("products")).hasSize(2);
        assertThat(first.path("truncated").asBoolean()).isTrue();
        assertThat(first.path("products").get(0).path("name").asText()).isEqualTo("Huile 1 L");
        as(schema, employee1);
        assertThat(ok("search_products", "{\"search\":\"riz\"}").path("products").get(0).path("purchasePrice")
                .isNull()).isTrue();
    }

    @Test
    @DisplayName("clients: a member sees the clients of their store only")
    void clientScope() throws Exception {
        data(http(employee1, HttpMethod.POST, "/api/v1/clients", Map.of("name", "Awa Ngono", "phone", "+237699000001")),
                201);
        data(http(managerB, HttpMethod.POST, "/api/v1/clients", Map.of("name", "Jean Mballa", "phone",
                "+237699000002")), 201);
        as(schema, employee1);
        JsonNode mine = ok("search_clients", "{}");
        List<String> names = new ArrayList<>();
        mine.path("clients").forEach(n -> names.add(n.path("name").asText()));
        assertThat(names).contains("Awa Ngono").doesNotContain("Jean Mballa");
        as(schema, owner);
        JsonNode all = ok("search_clients", "{\"search\":\"Mballa\"}");
        assertThat(all.path("clients").get(0).path("name").asText()).isEqualTo("Jean Mballa");
        assertThat(UUID_TEXT.matcher(all.toString()).find()).isFalse();
    }

    @Test
    @DisplayName("finance: owner reads it; an employee is refused by the registry and by the bean's own proxy")
    void financeGate() throws Exception {
        as(schema, owner);
        JsonNode result = ok("financial_result", "{}");
        assertThat(result.path("period").path("startDate").asText())
                .isEqualTo(LocalDate.now(KeevoZones.WAT).withDayOfMonth(1).toString());
        assertThat(result.has("comparisonWithPreviousPeriod")).isTrue();
        assertThat(UUID_TEXT.matcher(result.toString()).find()).isFalse();

        as(schema, employee1);
        AiToolResult refused = tools.call("financial_result", "{}", AiToolChannel.ASSISTANT);
        assertThat(refused).extracting(AiToolResult::outcome, AiToolResult::errorCode, AiToolResult::message)
                .containsExactly(Outcome.REFUSED, "FORBIDDEN", "Vous n'avez pas les droits nécessaires");
        assertThatThrownBy(() -> financeBean.financialResult(null, null, null, null))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @DisplayName("module disabled: not listed, refused with MODULE_NOT_ENTITLED and its French message")
    void moduleDisabled() throws Exception {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES ((SELECT id FROM "
                + "public.tenants WHERE schema_name = ?), 'invoicing', 'DISABLED', 'FREE') ON CONFLICT (tenant_id, "
                + "module_id) DO UPDATE SET status = EXCLUDED.status", schema);
        moduleRegistry.evict(schema);
        as(schema, owner);
        assertThat(allowed()).doesNotContain("unpaid_invoices", "unpaid_call_list", "invoice_collections");
        AiToolResult r = tools.call("unpaid_invoices", "{}", AiToolChannel.MCP);
        assertThat(r.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(r.errorCode()).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(r.message()).isEqualTo("Ce module n'est pas inclus dans votre abonnement");
        // the Spring AI callback hands the refusal to a model as French text, never an exception
        ToolCallback callback = tools.allowedToolCallbacks(AiToolChannel.ASSISTANT).stream()
                .filter(c -> c.getToolDefinition().name().equals("search_products")).findFirst().orElseThrow();
        assertThat(callback.call("{}")).startsWith("{");
    }

    @Test
    @DisplayName("tenant isolation: another company's owner never sees these documents, and vice versa")
    void tenantIsolation() throws Exception {
        String other = newTenant();
        Member otherOwner = member(other, "OWNER", null);
        settings(otherOwner);
        issue(otherOwner, create(otherOwner, "Client Autre", "+237690000009"));
        as(other, otherOwner);
        assertThat(clients(ok("unpaid_invoices", "{}"))).containsExactly("Client Autre");
        as(schema, owner);
        assertThat(clients(ok("unpaid_invoices", "{}"))).doesNotContain("Client Autre").hasSize(3);
    }

    @Test
    @DisplayName("audit: one row per call (tool, channel, actor, outcome, duration), never arguments or results")
    void audit() {
        as(schema, employee1);
        tools.call("unpaid_invoices", "{\"search\":\"Client E1\"}", AiToolChannel.ASSISTANT);
        tools.call("financial_result", "{}", AiToolChannel.MCP);
        var rows = jdbc.queryForList("SELECT c.tool, c.channel, c.outcome, c.error_code, c.actor_id, c.duration_ms "
                + "FROM public.ai_tool_calls c JOIN public.tenants t ON t.id = c.tenant_id WHERE t.schema_name = ? "
                + "ORDER BY c.id", schema);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("tool", "unpaid_invoices").containsEntry("channel", "ASSISTANT")
                .containsEntry("outcome", "OK").containsEntry("actor_id", employee1.user());
        assertThat(rows.get(1)).containsEntry("tool", "financial_result").containsEntry("outcome", "REFUSED")
                .containsEntry("error_code", "FORBIDDEN");
        assertThat(((Number) rows.get(0).get("duration_ms")).intValue()).isGreaterThanOrEqualTo(0);
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE "
                + "table_schema = 'public' AND table_name = 'ai_tool_calls'", String.class);
        assertThat(columns).containsExactlyInAnyOrder("id", "tenant_id", "actor_id", "tool", "channel", "outcome",
                "error_code", "duration_ms", "called_at", "access_token_id"); // 21.4.2: the MCP access token

        // retention: a row older than the cutoff goes, the fresh ones stay
        jdbc.update("INSERT INTO public.ai_tool_calls (tenant_id, tool, channel, outcome, duration_ms, called_at) "
                + "SELECT id, 'old_tool', 'MCP', 'OK', 1, now() - interval '200 days' FROM public.tenants "
                + "WHERE schema_name = ?", schema);
        callAudit.purgeBefore(Instant.now().minus(java.time.Duration.ofDays(180)));
        assertThat(jdbc.queryForList("SELECT c.tool FROM public.ai_tool_calls c JOIN public.tenants t ON t.id = "
                + "c.tenant_id WHERE t.schema_name = ?", String.class, schema))
                .containsExactlyInAnyOrder("unpaid_invoices", "financial_result");
    }
}
