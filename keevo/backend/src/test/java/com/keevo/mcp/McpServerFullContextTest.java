package com.keevo.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.mcp.token.application.service.McpToolLabels;
import com.keevo.mcp.token.application.service.McpTokenPurgeJob;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.infrastructure.security.access.AccessStateService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.9 — the external MCP server end to end on PostgreSQL with the production beans: the owner's personal access
 * tokens (create once-shown, list, revoke, bounds), the MCP protocol over Streamable HTTP with a token
 * ({@code initialize}, {@code tools/list}, {@code tools/call}), the token scope ∩ the owner's rights, the AI credit
 * counted once per served call, the refusals without data (revoked, expired, module off, owner no longer owner,
 * revocation cutoff, rate limit), the audit channel MCP, and two companies' tokens in parallel never mixing.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("MCP server — personal access tokens, protocol, quota, refusals and isolation on the full context (21.9)")
class McpServerFullContextTest extends FullContextIntegrationTest {

    static final String MCP = "/api/v1/mcp";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AccessStateService accessStates;
    @Autowired AiToolRegistry registry;
    @Autowired McpTokenPurgeJob purgeJob;

    record Member(UUID user, String schema, String jwt) {
    }

    private final List<String> schemas = new ArrayList<>();
    private final AtomicInteger rpcIds = new AtomicInteger();
    /** One client address per test (the failed-authentication brake is per IP). */
    private final String clientIp = "10.21." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    private String schema;
    private Member owner;
    private Member employee;

    @BeforeEach
    void setUp() {
        schema = newTenant();
        owner = owner(schema);
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        UUID employeeId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, store);
        employee = new Member(employeeId, schema, TestAccess.token(jdbc, jwtTokenProvider, employeeId, schema,
                "EMPLOYEE", "ACTIVE", store, false));
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.mcp_access_tokens WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", s);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?) AND module_id = 'mcp-access' AND status = 'DISABLED'", s);
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

    private Member owner(String tenant) {
        UUID user = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", Math.floorMod(user.getLeastSignificantBits(), 100_000_000L));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')", user, phone);
        return new Member(user, tenant, TestAccess.token(jdbc, jwtTokenProvider, user, tenant, "OWNER"));
    }

    private MvcResult rest(Member m, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + m.jwt());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
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

    /** Creates a token through the API and returns the creation data ({@code token} = clear token). */
    private JsonNode createToken(Member m, List<String> tools, Integer days) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "Claude du comptable");
        request.put("tools", tools);
        if (days != null) {
            request.put("expiresInDays", days);
        }
        return body(rest(m, HttpMethod.POST, MCP + "/tokens", request), 201).path("data");
    }

    private MvcResult mcp(String token, String method, Object params) throws Exception {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        if (!method.startsWith("notifications/")) {
            rpc.put("id", rpcIds.incrementAndGet());
        }
        rpc.put("method", method);
        if (params != null) {
            rpc.put("params", params);
        }
        var request = MockMvcRequestBuilders.post(MCP)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rpc));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.header("X-Forwarded-For", clientIp);
        MvcResult r = mockMvc.perform(request).andReturn();
        assertThat(TenantContext.getCurrentTenant()).as("tenant cleared after the request").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("session cleared").isNull();
        return r;
    }

    private JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return body(mcp(token, "tools/call", Map.of("name", tool, "arguments", args)), 200).path("result");
    }

    private long aiUsedToday(String tenant) {
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(c.used), 0) FROM public.usage_counters c JOIN "
                + "public.tenants t ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.metric = 'AI_CREDITS' AND "
                + "c.period = 'DAY'", Long.class, tenant);
        return used == null ? 0 : used;
    }

    private List<String> mcpCalls(String tenant) {
        return jdbc.queryForList("SELECT c.tool || ':' || c.outcome FROM public.ai_tool_calls c JOIN public.tenants t "
                + "ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.channel = 'MCP' ORDER BY c.called_at, c.id",
                String.class, tenant);
    }

    private void disableModule(String tenant) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES ((SELECT id FROM "
                + "public.tenants WHERE schema_name = ?), 'mcp-access', 'DISABLED', 'FREE') ON CONFLICT (tenant_id, "
                + "module_id) DO UPDATE SET status = 'DISABLED'", tenant);
        moduleRegistry.evict(tenant);
    }

    // ── tokens ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tokens: tools offered with French labels, created once-shown and hashed, listed, bounded, owner only")
    void tokenLifecycle() throws Exception {
        JsonNode tools = body(rest(owner, HttpMethod.GET, MCP + "/tools", null), 200).path("data");
        assertThat(tools.findValuesAsText("name")).contains("revenue_summary", "list_stores");
        assertThat(tools.findValuesAsText("label")).contains("Chiffre d'affaires et marge", "Boutiques");

        JsonNode created = createToken(owner, List.of("revenue_summary", "list_stores"), 30);
        String token = created.path("token").asText();
        assertThat(token).startsWith("kvp_").hasSize(47);
        assertThat(created.path("mcpUrl").asText()).endsWith(MCP);
        JsonNode view = created.path("accessToken");
        assertThat(view.path("hint").asText()).isEqualTo(token.substring(token.length() - 4));
        assertThat(view.path("tools").findValuesAsText("label")).containsExactly("Chiffre d'affaires et marge",
                "Boutiques");
        assertThat(view.path("expired").asBoolean()).isFalse();

        // stored as its SHA-256 only
        byte[] stored = jdbc.queryForObject("SELECT token_hash FROM public.mcp_access_tokens WHERE id = ?::uuid",
                byte[].class, view.path("id").asText());
        assertThat(stored).hasSize(32);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(token.substring(4, 20));
        Long expiresInDays = jdbc.queryForObject("SELECT round(extract(epoch FROM expires_at - created_at) / 86400) "
                + "FROM public.mcp_access_tokens WHERE id = ?::uuid", Long.class, view.path("id").asText());
        assertThat(expiresInDays).isEqualTo(30L);

        JsonNode list = body(rest(owner, HttpMethod.GET, MCP + "/tokens", null), 200).path("data");
        assertThat(list).hasSize(1);
        assertThat(list.toString()).doesNotContain(token).doesNotContain("token_hash");
        assertThat(list.get(0).path("name").asText()).isEqualTo("Claude du comptable");

        // validation (French, nothing stored)
        assertThat(body(rest(owner, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools",
                List.of("revenue_summary"), "expiresInDays", 12)), 400).path("error").asText())
                .contains("7, 30, 90 ou 365 jours");
        body(rest(owner, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools", List.of("drop_table"))), 400);
        body(rest(owner, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools", List.of())), 422);

        // default lifetime 90 days; at most 10 tokens per owner
        for (int i = 0; i < 9; i++) {
            createToken(owner, List.of("list_stores"), null);
        }
        Long defaults = jdbc.queryForObject("SELECT count(*) FROM public.mcp_access_tokens WHERE tenant_id = (SELECT "
                + "id FROM public.tenants WHERE schema_name = ?) AND expires_at - created_at = interval '90 days'",
                Long.class, schema);
        assertThat(defaults).isEqualTo(9L);
        assertThat(body(rest(owner, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools",
                List.of("list_stores"))), 409).path("domainCode").asText()).isEqualTo("MCP_TOKEN_LIMIT");

        // members never manage tokens
        body(rest(employee, HttpMethod.GET, MCP + "/tokens", null), 403);
        body(rest(employee, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools",
                List.of("list_stores"))), 403);

        // revoke: 204, then 404 for an unknown or already revoked token
        body(rest(owner, HttpMethod.DELETE, MCP + "/tokens/" + view.path("id").asText(), null), 204);
        assertThat(body(rest(owner, HttpMethod.DELETE, MCP + "/tokens/" + view.path("id").asText(), null), 404)
                .path("domainCode").asText()).isEqualTo("MCP_TOKEN_NOT_FOUND");
        Long audited = jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE action IN "
                + "('MCP_TOKEN_CREATED', 'MCP_TOKEN_REVOKED')", Long.class);
        assertThat(audited).isEqualTo(11L);
    }

    @Test
    @DisplayName("every AI tool has a short French label for the owner")
    void everyToolHasALabel() throws Exception {
        JsonNode tools = body(rest(owner, HttpMethod.GET, MCP + "/tools", null), 200).path("data");
        for (JsonNode t : tools) {
            assertThat(t.path("label").asText()).isNotBlank().doesNotContain("_");
        }
        assertThat(registry.allTools()).extracting(AiToolDescriptor::name)
                .allSatisfy(name -> assertThat(McpToolLabels.hasLabel(name)).as(name).isTrue());
    }

    // ── protocol ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("protocol: initialize, ping, tools/list = scope ∩ rights, tools/call audited MCP, one credit per served call")
    void protocol() throws Exception {
        String token = createToken(owner, List.of("revenue_summary", "list_stores"), null).path("token").asText();

        JsonNode init = body(mcp(token, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1"))), 200).path("result");
        assertThat(init.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.path("serverInfo").path("name").asText()).isEqualTo("keevo");
        assertThat(init.path("capabilities").has("tools")).isTrue();
        assertThat(init.path("instructions").asText()).contains("lecture seule");
        assertThat(body(mcp(token, "initialize", Map.of("protocolVersion", "1999-01-01")), 200)
                .path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        body(mcp(token, "ping", null), 200);
        assertThat(mcp(token, "notifications/initialized", null).getResponse().getStatus()).isEqualTo(202);

        JsonNode list = body(mcp(token, "tools/list", null), 200).path("result").path("tools");
        assertThat(list.findValuesAsText("name")).containsExactlyInAnyOrder("revenue_summary", "list_stores");
        JsonNode revenue = list.get(0).path("name").asText().equals("revenue_summary") ? list.get(0) : list.get(1);
        assertThat(revenue.path("inputSchema").path("type").asText()).isEqualTo("object");
        assertThat(revenue.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(revenue.path("title").asText()).isEqualTo("Chiffre d'affaires et marge");

        long before = aiUsedToday(schema);
        JsonNode served = call(token, "list_stores", Map.of());
        assertThat(served.path("isError").asBoolean()).isFalse();
        JsonNode stores = objectMapper.readTree(served.path("content").get(0).path("text").asText());
        assertThat(stores.toString()).contains("storeId");
        assertThat(aiUsedToday(schema)).isEqualTo(before + 1);

        // out of the token's scope: refused without calling it, nothing counted
        JsonNode outOfScope = call(token, "financial_result", Map.of());
        assertThat(outOfScope.path("isError").asBoolean()).isTrue();
        assertThat(outOfScope.path("content").get(0).path("text").asText()).contains("n'est pas autorisé");
        // invalid arguments: the registry's French refusal, nothing counted
        JsonNode invalid = call(token, "revenue_summary", Map.of("from", "10/10/2026"));
        assertThat(invalid.path("isError").asBoolean()).isTrue();
        assertThat(aiUsedToday(schema)).isEqualTo(before + 1);
        assertThat(mcpCalls(schema)).containsExactly("list_stores:OK", "revenue_summary:INVALID");

        // unknown method → JSON-RPC error
        JsonNode unknown = body(mcp(token, "resources/list", null), 200);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        // last use recorded
        assertThat(jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM public.mcp_access_tokens WHERE "
                + "tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)", Boolean.class, schema)).isTrue();
    }

    @Test
    @DisplayName("protocol: a tool the owner may no longer use is hidden and refused; AI credits used up → refused")
    void ownerRightsAndQuota() throws Exception {
        String token = createToken(owner, List.of("revenue_summary", "list_stores"), null).path("token").asText();
        // profitability off for the company: revenue_summary leaves the list, its call gets the REST refusal
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES ((SELECT id FROM "
                + "public.tenants WHERE schema_name = ?), 'profitability', 'DISABLED', 'FREE') ON CONFLICT (tenant_id, "
                + "module_id) DO UPDATE SET status = 'DISABLED'", schema);
        moduleRegistry.evict(schema);
        try {
            JsonNode list = body(mcp(token, "tools/list", null), 200).path("result").path("tools");
            assertThat(list.findValuesAsText("name")).containsExactly("list_stores");
            JsonNode scope = body(rest(owner, HttpMethod.GET, MCP + "/tokens", null), 200).path("data").get(0)
                    .path("tools");
            for (JsonNode t : scope) {
                assertThat(t.path("available").asBoolean()).as(t.path("name").asText())
                        .isEqualTo(!t.path("name").asText().equals("revenue_summary"));
            }
            JsonNode refused = call(token, "revenue_summary", Map.of());
            assertThat(refused.path("isError").asBoolean()).isTrue();
            assertThat(refused.path("content").get(0).path("text").asText()).contains("abonnement");
        } finally {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?) AND module_id = 'profitability' AND status = 'DISABLED'", schema);
            moduleRegistry.evict(schema);
        }

        // the free plan has no AI credit: the call is refused before the tool runs, nothing counted
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE schema_name = ?", schema);
        long before = aiUsedToday(schema);
        JsonNode quota = call(token, "list_stores", Map.of());
        assertThat(quota.path("isError").asBoolean()).isTrue();
        assertThat(quota.path("content").get(0).path("text").asText()).contains("Quota");
        assertThat(aiUsedToday(schema)).isEqualTo(before);
        assertThat(mcpCalls(schema)).containsExactly("revenue_summary:REFUSED");
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refusals: no / malformed / unknown / expired / revoked token → 401 MCP_TOKEN_INVALID, no data")
    void invalidTokens() throws Exception {
        JsonNode created = createToken(owner, List.of("list_stores"), 7);
        String token = created.path("token").asText();
        body(mcp(token, "ping", null), 200);

        for (String bad : new String[] {null, "abc", "kvp_" + "A".repeat(43), token + "x"}) {
            MvcResult r = mcp(bad, "tools/list", null);
            JsonNode refused = body(r, 401);
            assertThat(refused.path("domainCode").asText()).isEqualTo("MCP_TOKEN_INVALID");
            assertThat(refused.path("error").asText()).contains("invalide, expiré ou révoqué");
            assertThat(r.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        }
        // a JWT is no credential for the MCP endpoint
        body(mcp(owner.jwt(), "tools/list", null), 401);

        // revoked: refused at the very next request
        body(rest(owner, HttpMethod.DELETE, MCP + "/tokens/" + created.path("accessToken").path("id").asText(),
                null), 204);
        assertThat(body(mcp(token, "tools/list", null), 401).path("domainCode").asText())
                .isEqualTo("MCP_TOKEN_INVALID");

        // expired
        String second = createToken(owner, List.of("list_stores"), 7).path("token").asText();
        jdbc.update("UPDATE public.mcp_access_tokens SET created_at = now() - interval '8 days', expires_at = now() - "
                + "interval '1 day' WHERE tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)", schema);
        body(mcp(second, "ping", null), 401);
        assertThat(body(rest(owner, HttpMethod.GET, MCP + "/tokens", null), 200).path("data").get(0)
                .path("expired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("refusals: module not entitled → 403 MODULE_NOT_ENTITLED with no data; token list and revoke stay open")
    void moduleNotEntitled() throws Exception {
        JsonNode created = createToken(owner, List.of("list_stores"), null);
        String token = created.path("token").asText();
        disableModule(schema);
        JsonNode refused = body(mcp(token, "tools/call", Map.of("name", "list_stores", "arguments", Map.of())), 403);
        assertThat(refused.path("domainCode").asText()).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(refused.has("result")).isFalse();
        assertThat(mcpCalls(schema)).isEmpty();
        // creating needs the module; seeing and revoking never do
        body(rest(owner, HttpMethod.POST, MCP + "/tokens", Map.of("name", "x", "tools", List.of("list_stores"))),
                403);
        assertThat(body(rest(owner, HttpMethod.GET, MCP + "/tokens", null), 200).path("data")).hasSize(1);
        body(rest(owner, HttpMethod.DELETE, MCP + "/tokens/" + created.path("accessToken").path("id").asText(),
                null), 204);
    }

    @Test
    @DisplayName("refusals: owner no longer owner, membership off or sessions revoked after creation → 401, no data")
    void ownerChanges() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        body(mcp(token, "ping", null), 200);

        jdbc.update("UPDATE public.user_tenant_memberships SET tokens_valid_after = now() WHERE user_id = ?",
                owner.user());
        accessStates.invalidateUser(owner.user());
        assertThat(body(mcp(token, "ping", null), 401).path("domainCode").asText()).isEqualTo("MCP_TOKEN_INVALID");
        // the cut token no longer looks active nor uses the cap: removed when the owner lists
        assertThat(body(rest(owner, HttpMethod.GET, MCP + "/tokens", null), 200).path("data")).isEmpty();
        // a token created after the cutoff works
        String fresh = createToken(owner, List.of("list_stores"), null).path("token").asText();
        body(mcp(fresh, "ping", null), 200);

        jdbc.update("UPDATE public.user_tenant_memberships SET role = 'MANAGER' WHERE user_id = ?", owner.user());
        accessStates.invalidateUser(owner.user());
        assertThat(body(mcp(fresh, "ping", null), 401).path("domainCode").asText()).isEqualTo("MCP_TOKEN_INVALID");

        jdbc.update("UPDATE public.user_tenant_memberships SET role = 'OWNER', is_active = false WHERE user_id = ?",
                owner.user());
        accessStates.invalidateUser(owner.user());
        assertThat(body(mcp(fresh, "ping", null), 401).path("domainCode").asText()).isEqualTo("ACCOUNT_INACTIVE");

        jdbc.update("UPDATE public.user_tenant_memberships SET is_active = true WHERE user_id = ?", owner.user());
        jdbc.update("UPDATE public.tenants SET status = 'DELETED' WHERE schema_name = ?", schema);
        accessStates.invalidateUser(owner.user());
        try {
            assertThat(body(mcp(fresh, "ping", null), 401).path("domainCode").asText())
                    .isEqualTo("ACCOUNT_INACTIVE");
        } finally {
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE schema_name = ?", schema);
            accessStates.invalidateUser(owner.user());
        }
    }

    @Test
    @DisplayName("rate limit: beyond 30 requests a minute on a token → 429 RATE_LIMIT_EXCEEDED in French, no data")
    void rateLimit() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        for (int i = 0; i < 30; i++) {
            body(mcp(token, "ping", null), 200);
        }
        MvcResult limited = mcp(token, "tools/call", Map.of("name", "list_stores", "arguments", Map.of()));
        JsonNode refused = body(limited, 429);
        assertThat(refused.path("domainCode").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(refused.path("error").asText()).contains("Trop de requêtes");
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(mcpCalls(schema)).isEmpty();
    }

    // ── isolation ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isolation: two companies' tokens used in parallel each see only their own data, no context leak")
    void twoTenantsInParallel() throws Exception {
        String otherSchema = newTenant();
        Member otherOwner = owner(otherSchema);
        jdbc.update("UPDATE \"" + schema + "\".stores SET name = 'Boutique Akwa'");
        jdbc.update("UPDATE \"" + otherSchema + "\".stores SET name = 'Boutique Bonamoussadi'");
        String tokenA = createToken(owner, List.of("list_stores"), null).path("token").asText();
        String tokenB = createToken(otherOwner, List.of("list_stores"), null).path("token").asText();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean a = i % 2 == 0;
                Callable<Boolean> task = () -> {
                    String text = call(a ? tokenA : tokenB, "list_stores", Map.of()).path("content").get(0)
                            .path("text").asText();
                    boolean own = text.contains(a ? "Boutique Akwa" : "Boutique Bonamoussadi");
                    boolean other = text.contains(a ? "Boutique Bonamoussadi" : "Boutique Akwa");
                    return own && !other && TenantContext.getCurrentTenant() == null;
                };
                results.add(pool.submit(task));
            }
            for (Future<Boolean> f : results) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(mcpCalls(schema)).hasSize(10).allMatch(c -> c.equals("list_stores:OK"));
        assertThat(mcpCalls(otherSchema)).hasSize(10).allMatch(c -> c.equals("list_stores:OK"));
    }

    @Test
    @DisplayName("refusals: the owner disabled the module in « Mes modules » → 403 MODULE_DISABLED_BY_OWNER, no data")
    void moduleDisabledByOwner() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        body(rest(owner, HttpMethod.PUT, "/api/v1/tenants/me/modules/mcp-access/activation",
                Map.of("activated", false)), 200);
        try {
            JsonNode refused = body(mcp(token, "tools/call", Map.of("name", "list_stores", "arguments", Map.of())),
                    403);
            assertThat(refused.path("domainCode").asText()).isEqualTo("MODULE_DISABLED_BY_OWNER");
            assertThat(refused.path("error").asText()).contains("Mes modules");
            assertThat(refused.has("result")).isFalse();
            assertThat(mcpCalls(schema)).isEmpty();
        } finally {
            body(rest(owner, HttpMethod.PUT, "/api/v1/tenants/me/modules/mcp-access/activation",
                    Map.of("activated", true)), 200);
        }
    }

    @Test
    @DisplayName("brake: 20 failed authentications from one address → its next failures get 429; a valid token from "
            + "the same address is still served (shared provider / carrier addresses, 21.4.2)")
    void failedAuthenticationBrake() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        for (int i = 0; i < 20; i++) {
            body(mcp("kvp_" + "B".repeat(43), "ping", null), 401);
        }
        MvcResult braked = mcp("kvp_" + "C".repeat(43), "ping", null);
        assertThat(body(braked, 429).path("domainCode").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(braked.getResponse().getHeader("Retry-After")).isEqualTo("60");
        body(mcp(token, "ping", null), 200);
    }

    @Test
    @DisplayName("21.4.2: the audit names the access token; created / revoked audit actions and their French labels")
    void auditNamesTheToken() throws Exception {
        JsonNode created = createToken(owner, List.of("list_stores"), null);
        String token = created.path("token").asText();
        String id = created.path("accessToken").path("id").asText();
        call(token, "list_stores", Map.of());
        assertThat(jdbc.queryForObject("SELECT access_token_id::text FROM public.ai_tool_calls c JOIN public.tenants t "
                + "ON t.id = c.tenant_id WHERE t.schema_name = ? AND c.channel = 'MCP'", String.class, schema))
                .isEqualTo(id);
        body(rest(owner, HttpMethod.DELETE, MCP + "/tokens/" + id, null), 204);
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, entity_label FROM \"" + schema
                + "\".audit_log WHERE action LIKE 'MCP_TOKEN_%' ORDER BY occurred_at, action");
        assertThat(audit).extracting(r -> r.get("action") + ":" + r.get("entity_label"))
                .containsExactly("MCP_TOKEN_CREATED:Claude du comptable", "MCP_TOKEN_REVOKED:Claude du comptable");
        assertThat(com.keevo.shared.infrastructure.export.AuditLabels.ACTIONS.get("MCP_TOKEN_CREATED"))
                .isEqualTo("Accès IA externe créé");
        assertThat(com.keevo.shared.infrastructure.export.AuditLabels.ACTIONS.get("MCP_TOKEN_REVOKED"))
                .isEqualTo("Accès IA externe révoqué");
    }

    @Test
    @DisplayName("21.4.2: last use moves forward after a minute, not before")
    void lastUseThrottled() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        body(mcp(token, "ping", null), 200);
        String select = "SELECT last_used_at FROM public.mcp_access_tokens WHERE tenant_id = (SELECT id FROM "
                + "public.tenants WHERE schema_name = ?)";
        java.sql.Timestamp first = jdbc.queryForObject(select, java.sql.Timestamp.class, schema);
        body(mcp(token, "ping", null), 200);
        assertThat(jdbc.queryForObject(select, java.sql.Timestamp.class, schema)).isEqualTo(first);
        jdbc.update("UPDATE public.mcp_access_tokens SET last_used_at = now() - interval '2 minutes' WHERE tenant_id = "
                + "(SELECT id FROM public.tenants WHERE schema_name = ?)", schema);
        java.sql.Timestamp old = jdbc.queryForObject(select, java.sql.Timestamp.class, schema);
        body(mcp(token, "ping", null), 200);
        assertThat(jdbc.queryForObject(select, java.sql.Timestamp.class, schema)).isAfter(old);
    }

    @Test
    @DisplayName("21.4.2: an encoded MCP path never reaches the tools with a JWT; GET on the endpoint → 405")
    void pathsAndMethods() throws Exception {
        MvcResult encoded = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/%6Dcp")
                .header("Authorization", "Bearer " + employee.jwt())
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":"
                        + "\"list_stores\"}}")).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        // the decoded path is the MCP endpoint: the MCP credential is required (a JWT is not one) — refused, no tool
        assertThat(encoded.getResponse().getStatus()).isIn(400, 401);
        assertThat(mcpCalls(schema)).isEmpty();
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        MvcResult get = mockMvc.perform(MockMvcRequestBuilders.get(MCP).header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", clientIp)).andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(405);
    }

    @Test
    @DisplayName("21.4.2: a suspended company's tokens are refused (403 ACCOUNT_SUSPENDED), nothing served or counted")
    void suspendedCompany() throws Exception {
        String token = createToken(owner, List.of("list_stores"), null).path("token").asText();
        jdbc.update("UPDATE public.tenants SET status = 'SUSPENDED' WHERE schema_name = ?", schema);
        try {
            accessStates.invalidateTenant(schema);
            JsonNode refused = body(mcp(token, "tools/call", Map.of("name", "list_stores")), 403);
            assertThat(refused.path("domainCode").asText()).isEqualTo("ACCOUNT_SUSPENDED");
            assertThat(refused.path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(mcpCalls(schema)).isEmpty();
        } finally {
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE' WHERE schema_name = ?", schema);
            accessStates.invalidateTenant(schema);
        }
    }

    @Test
    @DisplayName("21.4.2: expired tokens never hold a place under the cap of 10")
    void expiredTokensOutsideTheCap() throws Exception {
        for (int i = 0; i < 10; i++) {
            createToken(owner, List.of("list_stores"), 7);
        }
        jdbc.update("UPDATE public.mcp_access_tokens SET created_at = now() - interval '10 days', expires_at = now() - "
                + "interval '3 days' WHERE tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)", schema);
        createToken(owner, List.of("list_stores"), 7); // 201: the 10 expired ones are not counted
    }

    @Test
    @DisplayName("purge: tokens expired more than 30 days ago are deleted, recent and live ones kept")
    void purgeExpired() throws Exception {
        for (int i = 0; i < 3; i++) {
            createToken(owner, List.of("list_stores"), 7);
        }
        List<UUID> ids = jdbc.queryForList("SELECT id FROM public.mcp_access_tokens WHERE tenant_id = (SELECT id FROM "
                + "public.tenants WHERE schema_name = ?) ORDER BY created_at", UUID.class, schema);
        jdbc.update("UPDATE public.mcp_access_tokens SET created_at = now() - interval '40 days', expires_at = now() - "
                + "interval '33 days' WHERE id = ?", ids.get(0));
        jdbc.update("UPDATE public.mcp_access_tokens SET created_at = now() - interval '20 days', expires_at = now() - "
                + "interval '13 days' WHERE id = ?", ids.get(1));
        purgeJob.purge();
        assertThat(jdbc.queryForList("SELECT id FROM public.mcp_access_tokens WHERE tenant_id = (SELECT id FROM "
                + "public.tenants WHERE schema_name = ?)", UUID.class, schema))
                .containsExactlyInAnyOrder(ids.get(1), ids.get(2));
    }
}
