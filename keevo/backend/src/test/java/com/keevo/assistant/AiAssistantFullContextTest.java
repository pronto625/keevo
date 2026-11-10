package com.keevo.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.adapter.out.model.ScriptedAssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.conversation.adapter.out.persistence.AiConversationMemberPurger;
import com.keevo.identity.api.EmployeeCommandPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.4 — the assistant end to end on PostgreSQL with the production beans and the scripted model: module table
 * provisioned on first use, conversations per user (CRUD, bulk delete, isolation), free-text questions answering with
 * tools called on the request thread (audit ASSISTANT), the AI credit counted once per answer and never on a failure
 * or a refused quota, guided topics free, and isolation (employee scope, disabled module, other tenant).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AI assistant — conversations, chat, quota, guided topics and isolation on the full context (21.4)")
class AiAssistantFullContextTest extends FullContextIntegrationTest {

    static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AssistantModel assistantModel;
    @Autowired EmployeeCommandPort employeeCommands;
    @Autowired AiConversationMemberPurger memberPurger;

    record Member(UUID user, String role, UUID store, String token) {
    }

    private final List<String> schemas = new ArrayList<>();
    private String schema;
    private UUID storeA;
    private UUID storeB;
    private Member owner;
    private Member employee;
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
        employee = member(schema, "EMPLOYEE", storeA);
        managerB = member(schema, "MANAGER", storeB);
        settings(owner);
        issue(employee, invoice(employee, "Client Employé", "+237690000011"));
        issue(managerB, invoice(managerB, "Client Gérant", "+237690000012"));
    }

    @AfterEach
    void tearDown() {
        ((ScriptedAssistantModel) assistantModel).reset();
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", s);
            jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = (SELECT id FROM public.tenants "
                    + "WHERE schema_name = ?)", s);
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
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s
                    : objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private JsonNode body(MvcResult r, int status) throws Exception {
        String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(text).isEqualTo(status);
        return text.isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(text);
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        return body(r, status).path("data");
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

    private String invoice(Member m, String client, String phone) throws Exception {
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

    private void script(String json) throws Exception {
        ((ScriptedAssistantModel) assistantModel).useScript(objectMapper.readTree(json));
    }

    private long aiUsedToday(String tenant) {
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(c.used), 0) FROM public.usage_counters c JOIN "
                + "public.tenants t ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.metric = 'AI_CREDITS' AND "
                + "c.period = 'DAY'", Long.class, tenant);
        return used == null ? 0 : used;
    }

    private List<String> assistantCalls(String tenant) {
        return jdbc.queryForList("SELECT c.tool || ':' || c.outcome FROM public.ai_tool_calls c JOIN public.tenants t "
                + "ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.channel = 'ASSISTANT' ORDER BY c.called_at, "
                + "c.id", String.class, tenant);
    }

    private static Map<String, Object> question(String text) {
        return Map.of("message", text);
    }

    // ── Story 16.6 reprise (RGPD) — member removal ───────────────────────────────────────────────────────

    @Test
    @DisplayName("16.6 reprise — removing a member (super-admin port) erases their conversations; the others' stay")
    void memberRemovalErasesTheirConversations() throws Exception {
        data(http(owner, HttpMethod.POST, "/api/v1/ai/conversations", Map.of("firstMessage", "Point du jour")), 201);
        for (String q : List.of("Mes ventes du client Awa +237690000011", "Mes impayés")) {
            data(http(employee, HttpMethod.POST, "/api/v1/ai/conversations", Map.of("firstMessage", q)), 201);
        }
        String table = "\"" + schema + "\".ai_conversations";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                employee.user())).isEqualTo(2);
        UUID employeeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".employees WHERE user_id = ?",
                UUID.class, employee.user());
        // a former owner's MCP token for this company (McpTokenMemberPurger)
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'EMPLOYEE') ON CONFLICT (id) DO NOTHING",
                employee.user(), "+2376" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(100_000_000)));
        jdbc.update("INSERT INTO public.mcp_access_tokens (id, tenant_id, user_id, name, token_hash, hint, tools, "
                + "expires_at) SELECT gen_random_uuid(), t.id, ?, 'Claude', decode(md5(random()::text) "
                + "|| md5(random()::text), 'hex'), 'abcd', ARRAY['sales_summary'], now() + interval '30 days' "
                + "FROM public.tenants t WHERE t.schema_name = ?", employee.user(), schema);

        TenantContext.setCurrentTenant(schema);
        try {
            employeeCommands.remove(schema, owner.user(), employeeId);
        } finally {
            TenantContext.clear();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                employee.user())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mcp_access_tokens WHERE user_id = ?", Integer.class,
                employee.user())).isZero();
        assertThat(data(http(owner, HttpMethod.GET, "/api/v1/ai/conversations", null), 200)).hasSize(1);
    }

    @Test
    @DisplayName("16.6 reprise — a company where the assistant never stored anything: the member purge is a no-op")
    void memberPurgeWithoutModuleTable() {
        String fresh = newTenant();
        // a new company may get the module table at provisioning: drop it to stand for a never-provisioned one
        jdbc.execute("DROP TABLE IF EXISTS \"" + fresh + "\".ai_conversations");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class,
                "\"" + fresh + "\".ai_conversations")).isNull();

        memberPurger.purgeMember(fresh, UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class,
                "\"" + fresh + "\".ai_conversations")).isNull();
    }

    // ── conversations ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("conversations: create (given / generated title), list, read, save, delete one and several, per user")
    void conversations() throws Exception {
        JsonNode first = data(http(owner, HttpMethod.POST, "/api/v1/ai/conversations",
                Map.of("firstMessage", "Mon chiffre d'affaires", "title", "Point du lundi")), 201);
        assertThat(first.path("title").asText()).isEqualTo("Point du lundi");
        assertThat(first.path("messages").isArray()).isTrue();
        assertThat(first.path("messageCount").asInt()).isZero();
        String id = first.path("id").asText();
        JsonNode generated = data(http(owner, HttpMethod.POST, "/api/v1/ai/conversations",
                Map.of("firstMessage", "Quels sont mes impayés les plus anciens ce mois ?")), 201);
        assertThat(generated.path("title").asText()).isEqualTo("Quels sont mes impayés les plus");

        String save = """
                {"messages":[{"id":"m1","role":"user","text":"Bonjour"},{"id":"m2","role":"assistant","text":"Salut",
                "stats":[{"icon":"cash-outline","label":"CA","value":"10 000 FCFA"}]}],
                "aiHistory":[{"role":"user","content":"Bonjour"},{"role":"assistant","content":"Salut"}],
                "messageCount":2}""";
        body(http(owner, HttpMethod.PUT, "/api/v1/ai/conversations/" + id, save), 204);
        JsonNode read = data(http(owner, HttpMethod.GET, "/api/v1/ai/conversations/" + id, null), 200);
        assertThat(read.path("title").asText()).isEqualTo("Point du lundi"); // kept when absent
        assertThat(read.path("messageCount").asInt()).isEqualTo(2);
        assertThat(read.path("messages").get(1).path("stats").get(0).path("value").asText()).isEqualTo("10 000 FCFA");
        assertThat(read.path("aiHistory").get(0).path("content").asText()).isEqualTo("Bonjour");

        JsonNode list = data(http(owner, HttpMethod.GET, "/api/v1/ai/conversations", null), 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo(id); // most recently updated first
        assertThat(list.get(0).has("messages")).isFalse();

        // another member of the company never sees, changes or deletes it
        body(http(employee, HttpMethod.GET, "/api/v1/ai/conversations/" + id, null), 404);
        assertThat(body(http(employee, HttpMethod.PUT, "/api/v1/ai/conversations/" + id, save), 404)
                .path("domainCode").asText()).isEqualTo("AI_CONVERSATION_NOT_FOUND");
        assertThat(data(http(employee, HttpMethod.GET, "/api/v1/ai/conversations", null), 200)).isEmpty();
        body(http(employee, HttpMethod.DELETE, "/api/v1/ai/conversations/batch", Map.of("ids", List.of(id))), 204);
        body(http(owner, HttpMethod.GET, "/api/v1/ai/conversations/" + id, null), 200);

        // validation: Bref bounds (Keevo answers body validation with 422 VALIDATION_ERROR)
        body(http(owner, HttpMethod.POST, "/api/v1/ai/conversations", Map.of("firstMessage", " ")), 422);
        body(http(owner, HttpMethod.DELETE, "/api/v1/ai/conversations/batch", Map.of("ids", List.of())), 422);

        body(http(owner, HttpMethod.DELETE, "/api/v1/ai/conversations/batch", Map.of("ids",
                List.of(id, generated.path("id").asText()))), 204);
        assertThat(data(http(owner, HttpMethod.GET, "/api/v1/ai/conversations", null), 200)).isEmpty();
        body(http(owner, HttpMethod.DELETE, "/api/v1/ai/conversations/" + id, null), 204); // idempotent
    }

    // ── chat ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chat: tools run on the request thread (audited ASSISTANT), no identifier, one credit per answer only")
    void chatCountsOncePerAnswer() throws Exception {
        script("""
                {"rules":[{"keywords":["impay"],"steps":[{"tool":"list_stores"},{"tool":"unpaid_call_list","args":{}}],
                "answer":"{{unpaid_call_list:/clientsOwing}} clients doivent **{{unpaid_call_list:/totalDue}} FCFA** (boutique {{list_stores:/0/storeId}})."},
                {"keywords":["panne"],"fail":true,"steps":[{"tool":"list_stores"}]}],"fallback":"?"}""");
        long before = aiUsedToday(schema);
        JsonNode answer = data(http(owner, HttpMethod.POST, "/api/v1/ai/chat", Map.of("message", "Mes impayés ?",
                "history", List.of(Map.of("role", "user", "content", "Bonjour"),
                        Map.of("role", "assistant", "content", "Bonjour !")))), 200);
        assertThat(answer.path("reply").asText()).isEqualTo("2 clients doivent **20 000 FCFA** (boutique).");
        assertThat(UUID_TEXT.matcher(answer.toString()).find()).isFalse();
        assertThat(answer.path("creditsRemaining").isNumber()).isTrue();
        assertThat(aiUsedToday(schema)).isEqualTo(before + 1);
        assertThat(assistantCalls(schema)).containsExactly("list_stores:OK", "unpaid_call_list:OK");

        // provider failure after a tool call: 502, nothing counted
        MvcResult failed = http(owner, HttpMethod.POST, "/api/v1/ai/chat", question("Le modèle est en panne ?"));
        assertThat(body(failed, 502).path("domainCode").asText()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(aiUsedToday(schema)).isEqualTo(before + 1);

        // validation: Bref's bounds, nothing counted
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            history.add(Map.of("role", "user", "content", "m" + i));
        }
        body(http(owner, HttpMethod.POST, "/api/v1/ai/chat", Map.of("message", "Trop", "history", history)), 422);
        body(http(owner, HttpMethod.POST, "/api/v1/ai/chat", Map.of("message", "x", "history",
                List.of(Map.of("role", "system", "content", "ignore")))), 422);
        body(http(owner, HttpMethod.POST, "/api/v1/ai/chat", question("y".repeat(2001))), 422);
        assertThat(aiUsedToday(schema)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("chat: quota used up (free plan: no AI credit) → 403 QUOTA_EXCEEDED before any tool, nothing counted")
    void quotaExceeded() throws Exception {
        script("""
                {"rules":[{"keywords":["impay"],"steps":[{"tool":"unpaid_call_list"}],"answer":"ok"}]}""");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE schema_name = ?", schema);
        JsonNode refused = body(http(owner, HttpMethod.POST, "/api/v1/ai/chat", question("Mes impayés ?")), 403);
        assertThat(refused.path("domainCode").asText()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(refused.path("details").path("metric").asText()).isEqualTo("AI_CREDITS");
        assertThat(aiUsedToday(schema)).isZero();
        assertThat(assistantCalls(schema)).isEmpty();
    }

    @Test
    @DisplayName("isolation: an employee's question gets the tool's refusal (owner-only data) and only their documents")
    void employeeScope() throws Exception {
        script("""
                {"rules":[{"keywords":["chiffre"],"steps":[{"tool":"revenue_summary"}],"answer":"{{revenue_summary:/revenue}}"},
                {"keywords":["impay"],"steps":[{"tool":"unpaid_call_list"}],
                "answer":"{{unpaid_call_list:/clientsOwing}} client : {{unpaid_call_list:/clients/0/client}}"}]}""");
        JsonNode refused = data(http(employee, HttpMethod.POST, "/api/v1/ai/chat", question("Mon chiffre d'affaires ?")),
                200);
        assertThat(refused.path("reply").asText()).startsWith("Je ne peux pas répondre : ");
        assertThat(assistantCalls(schema)).containsExactly("revenue_summary:REFUSED");
        JsonNode own = data(http(employee, HttpMethod.POST, "/api/v1/ai/chat", question("Mes impayés ?")), 200);
        assertThat(own.path("reply").asText()).isEqualTo("1 client : Client Employé");

        // guided topics: the employee's topics only, scoped like REST
        JsonNode topics = data(http(employee, HttpMethod.GET, "/api/v1/ai/topics", null), 200);
        List<String> ids = topics.path("topics").findValuesAsText("id");
        assertThat(ids).contains("overdue", "call_list", "collections").doesNotContain("shops", "revenue");
        assertThat(topics.path("freeTextAvailable").asBoolean()).isTrue();
        JsonNode calls = data(http(employee, HttpMethod.GET, "/api/v1/ai/topics/call_list", null), 200);
        assertThat(calls.path("rows").findValuesAsText("label")).containsExactly("Client Employé");
        JsonNode shops = body(http(employee, HttpMethod.GET, "/api/v1/ai/topics/shops?period=month", null), 403);
        assertThat(shops.path("domainCode").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("guided topics: free (no credit), French figures, owner sees every store; bad inputs → 400")
    void guidedTopics() throws Exception {
        long before = aiUsedToday(schema);
        JsonNode topics = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics", null), 200);
        assertThat(topics.path("topics").findValuesAsText("id")).contains("diagnostic", "revenue", "overdue",
                "collections", "sellers", "shops", "customers", "call_list");
        assertThat(topics.path("periods").findValuesAsText("id")).containsExactly("today", "week", "month", "year");

        JsonNode diagnostic = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/diagnostic", null), 200);
        assertThat(diagnostic.path("period").path("id").asText()).isEqualTo("month");
        assertThat(stat(diagnostic, "unpaidInvoices")).isEqualTo(2);
        assertThat(stat(diagnostic, "totalDue")).isEqualTo(20_000);
        assertThat(diagnostic.path("text").asText()).contains("2 factures non soldées");

        JsonNode revenue = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/revenue?period=today", null), 200);
        assertThat(stat(revenue, "revenue")).isEqualTo(20_000); // the two invoices issued today
        assertThat(revenue.path("title").asText()).isEqualTo("Chiffre d'affaires — aujourd'hui");
        JsonNode onlyB = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/revenue?period=today&storeId=" + storeB,
                null), 200);
        assertThat(stat(onlyB, "revenue")).isEqualTo(10_000);
        JsonNode compare = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/revenue?period=today&compare=true",
                null), 200);
        assertThat(stat(compare, "revenue")).isEqualTo(20_000);
        assertThat(stat(compare, "previousRevenue")).isZero();

        JsonNode callList = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/call_list", null), 200);
        assertThat(callList.path("rows").findValuesAsText("phone")).contains("+237690000011", "+237690000012");
        JsonNode customer = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/customers?search=Client G", null),
                200);
        assertThat(customer.path("rows").findValuesAsText("label")).containsExactly("Client Gérant");
        data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/collections?period=week&compare=true", null), 200);
        data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/sellers?period=year", null), 200);
        data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/shops?period=week", null), 200);
        data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/overdue", null), 200);
        assertThat(UUID_TEXT.matcher(callList.toString() + diagnostic + revenue).find()).isFalse();

        body(http(owner, HttpMethod.GET, "/api/v1/ai/topics/stock", null), 400);
        body(http(owner, HttpMethod.GET, "/api/v1/ai/topics/revenue?period=decade", null), 400);
        body(http(owner, HttpMethod.GET, "/api/v1/ai/topics/overdue?compare=true", null), 400);
        assertThat(aiUsedToday(schema)).isEqualTo(before); // never counted
    }

    @Test
    @DisplayName("guided figures: overdue, collections (+ comparison), sellers and shops match the documents of the day")
    void guidedFigures() throws Exception {
        java.time.Instant noon = java.time.LocalDate.now(com.keevo.shared.domain.time.KeevoZones.WAT).atTime(12, 0)
                .atZone(com.keevo.shared.domain.time.KeevoZones.WAT).toInstant();
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                        + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, "
                        + "5000, 0)", UUID.randomUUID(), java.sql.Timestamp.from(noon), employee.user(),
                java.sql.Timestamp.from(noon), storeA);
        String managerInvoice = invoice(managerB, "Client Payeur", "+237690000013");
        issue(managerB, managerInvoice);
        data(http(owner, HttpMethod.POST, "/api/v1/invoices/" + managerInvoice + "/payments", Map.of("amount", 4_000,
                "paidAt", java.time.LocalDate.now(com.keevo.shared.domain.time.KeevoZones.WAT).toString(),
                "method", "Espèces")), 201);

        JsonNode collections = data(http(owner, HttpMethod.GET,
                "/api/v1/ai/topics/collections?period=week&compare=true", null), 200);
        assertThat(stat(collections, "collected")).isEqualTo(4_000);
        assertThat(stat(collections, "payments")).isEqualTo(1);
        assertThat(stat(collections, "previousCollected")).isZero();
        assertThat(stat(collections, "difference")).isEqualTo(4_000);
        assertThat(stat(collections, "totalDue")).isEqualTo(26_000);
        assertThat(collections.path("rows").get(0).path("amount").asLong()).isEqualTo(4_000);

        JsonNode overdue = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/overdue", null), 200);
        assertThat(stat(overdue, "overdueInvoices")).isZero();
        assertThat(stat(overdue, "unpaidInvoices")).isEqualTo(3);

        JsonNode sellers = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/sellers?period=today", null), 200);
        List<Long> amounts = new ArrayList<>();
        sellers.path("rows").forEach(r -> amounts.add(r.path("amount").asLong()));
        assertThat(amounts).containsExactly(20_000L, 15_000L); // manager 2 invoices, employee invoice + sale
        assertThat(stat(sellers, "revenue")).isEqualTo(35_000);
        assertThat(sellers.path("rows").get(0).path("detail").asText()).isEqualTo("2 ventes · panier moyen 10 000 FCFA");

        JsonNode shops = data(http(owner, HttpMethod.GET, "/api/v1/ai/topics/shops?period=today", null), 200);
        assertThat(shops.path("rows").findValuesAsText("label")).containsExactly("Boutique Bonapriso",
                jdbc.queryForObject("SELECT name FROM \"" + schema + "\".stores WHERE id = ?", String.class, storeA));
        assertThat(shops.path("rows").get(0).path("amount").asLong()).isEqualTo(20_000);
        assertThat(stat(shops, "revenue")).isEqualTo(35_000);
    }

    @Test
    @DisplayName("roles: a VIEWER asks and saves (store scope), a MANAGER sees only their store; a rename keeps the chat")
    void viewerAndManager() throws Exception {
        Member viewer = member(schema, "VIEWER", storeA);
        script("""
                {"rules":[{"keywords":["impay"],"steps":[{"tool":"unpaid_call_list"}],
                "answer":"{{unpaid_call_list:/clientsOwing}} client : {{unpaid_call_list:/clients/0/client}}"}]}""");
        assertThat(data(http(viewer, HttpMethod.POST, "/api/v1/ai/chat", question("Mes impayés ?")), 200)
                .path("reply").asText()).isEqualTo("1 client : Client Employé");
        assertThat(data(http(managerB, HttpMethod.POST, "/api/v1/ai/chat", question("Mes impayés ?")), 200)
                .path("reply").asText()).isEqualTo("1 client : Client Gérant");
        assertThat(data(http(managerB, HttpMethod.GET, "/api/v1/ai/topics/call_list", null), 200).path("rows")
                .findValuesAsText("label")).containsExactly("Client Gérant");

        String id = data(http(viewer, HttpMethod.POST, "/api/v1/ai/conversations",
                Map.of("firstMessage", "Impayés", "title", "Relances")), 201).path("id").asText();
        body(http(viewer, HttpMethod.PUT, "/api/v1/ai/conversations/" + id,
                "{\"messages\":[{\"id\":\"m1\",\"text\":\"Bonjour\"}],\"aiHistory\":[],\"messageCount\":1}"), 204);
        body(http(viewer, HttpMethod.PUT, "/api/v1/ai/conversations/" + id, "{\"title\":\"Relances de lundi\"}"), 204);
        JsonNode kept = data(http(viewer, HttpMethod.GET, "/api/v1/ai/conversations/" + id, null), 200);
        assertThat(kept.path("title").asText()).isEqualTo("Relances de lundi");
        assertThat(kept.path("messageCount").asInt()).isEqualTo(1);
        assertThat(kept.path("messages").get(0).path("text").asText()).isEqualTo("Bonjour");
        body(http(viewer, HttpMethod.DELETE, "/api/v1/ai/conversations/" + id, null), 204);
    }

    private static long stat(JsonNode answer, String key) {
        for (JsonNode s : answer.path("stats")) {
            if (key.equals(s.path("key").asText())) {
                return s.path("value").asLong();
            }
        }
        throw new AssertionError("no stat " + key + " in " + answer);
    }

    @Test
    @DisplayName("disabled modules: a tool module off refuses its topic; the assistant off by the owner refuses all")
    void disabledModules() throws Exception {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES ((SELECT id FROM "
                + "public.tenants WHERE schema_name = ?), 'invoicing', 'DISABLED', 'FREE') ON CONFLICT (tenant_id, "
                + "module_id) DO UPDATE SET status = EXCLUDED.status", schema);
        moduleRegistry.evict(schema);
        JsonNode refused = body(http(owner, HttpMethod.GET, "/api/v1/ai/topics/call_list", null), 403);
        assertThat(refused.path("domainCode").asText()).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(data(http(owner, HttpMethod.GET, "/api/v1/ai/topics", null), 200).path("topics")
                .findValuesAsText("id")).doesNotContain("call_list", "overdue");

        jdbc.update("INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at) "
                + "VALUES ((SELECT id FROM public.tenants WHERE schema_name = ?), 'ai-assistant', false, now()) "
                + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET activated = false", schema);
        moduleRegistry.evict(schema);
        assertThat(body(http(owner, HttpMethod.GET, "/api/v1/ai/topics", null), 403).path("domainCode").asText())
                .isEqualTo("MODULE_DISABLED_BY_OWNER");
        body(http(owner, HttpMethod.POST, "/api/v1/ai/chat", question("Bonjour")), 403);
        body(http(owner, HttpMethod.GET, "/api/v1/ai/conversations", null), 403);
    }

    @Test
    @DisplayName("other tenant: never sees this company's conversations nor its debtors")
    void otherTenant() throws Exception {
        String id = data(http(owner, HttpMethod.POST, "/api/v1/ai/conversations",
                Map.of("firstMessage", "Bonjour", "title", "Privée")), 201).path("id").asText();
        String other = newTenant();
        Member otherOwner = member(other, "OWNER", null);
        settings(otherOwner);
        issue(otherOwner, invoice(otherOwner, "Client Autre", "+237690000019"));
        body(http(otherOwner, HttpMethod.GET, "/api/v1/ai/conversations/" + id, null), 404);
        JsonNode calls = data(http(otherOwner, HttpMethod.GET, "/api/v1/ai/topics/call_list", null), 200);
        assertThat(calls.path("rows").findValuesAsText("label")).containsExactly("Client Autre");
        script("""
                {"rules":[{"keywords":["impay"],"steps":[{"tool":"unpaid_call_list"}],
                "answer":"{{unpaid_call_list:/clientsOwing}} : {{unpaid_call_list:/clients/0/client}}"}]}""");
        assertThat(data(http(otherOwner, HttpMethod.POST, "/api/v1/ai/chat", question("Mes impayés ?")), 200)
                .path("reply").asText()).isEqualTo("1 : Client Autre");
    }
}
