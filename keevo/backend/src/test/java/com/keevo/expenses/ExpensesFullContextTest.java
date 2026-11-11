package com.keevo.expenses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.10 — the « Dépenses » module end to end with the production security filter, JWTs, module gate and the
 * real sync chain: provisioning on first use, Bref rules, roles (user decision 2026-11-11: the OWNER only by default,
 * a built-in MANAGER, EMPLOYEE or VIEWER refused everywhere; a member whose owner-defined role grants the rights acts
 * in their store), store scope (owner / authorised manager), keyset list with totals, edit / delete,
 * offline operations (DUPLICATE, last write wins, tombstones), module disabled.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Expenses — REST, store scope, sync push / pull (Story 20.10)")
class ExpensesFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeA;
    private UUID storeB;
    private String ownerToken;
    private String employeeToken;
    /** Reprise 11/11/2026 — a MANAGER whose owner-defined role ticks the three expense rights (store scope tests). */
    private String managerToken;
    /** Reprise 11/11/2026 — a built-in MANAGER: expenses are the owner's by default. */
    private String plainManagerToken;
    private String viewerToken;
    private Instant clock = Instant.now().minusSeconds(600);
    private final Map<String, String> devices = new HashMap<>();

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, 'Boutique Bonapriso', 'STORE', true, now() + interval '1 second', now())", storeB);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeB);
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        employeeToken = TestAccess.token(jdbc, jwtTokenProvider, employeeId, schema, "EMPLOYEE", "ACTIVE", storeB,
                false);
        managerToken = memberToken("MANAGER", "Marie", storeB);
        grantExpenses(managerToken);
        plainManagerToken = memberToken("MANAGER", "Luc", storeB);
        viewerToken = memberToken("VIEWER", "Jean", storeB);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE public.user_tenant_memberships SET custom_role_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenant_roles WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return status == 204 ? null : objectMapper.readTree(body(r));
    }

    private void error(MvcResult r, int status, String domainCode) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(objectMapper.readTree(body(r)).path("domainCode").asText()).isEqualTo(domainCode);
    }

    private static Map<String, Object> expense(Object amount, String category, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", amount);
        m.put("category", category);
        if (date != null) {
            m.put("expenseDate", date);
        }
        return m;
    }

    private JsonNode create(String token, Map<String, Object> body) throws Exception {
        return ok(call(token, HttpMethod.POST, "/api/v1/expenses", body), 201).path("data");
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema + "\".expenses", Integer.class);
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

    @SafeVarargs
    private Map<String, JsonNode> push(String token, Map<String, Object>... ops) throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/sync/push")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("deviceId",
                        devices.computeIfAbsent(token, t -> UUID.randomUUID().toString()),
                        "operations", List.of(ops))))).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode result : objectMapper.readTree(body(r)).path("data").path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }
        return byId;
    }

    private static JsonNode result(Map<String, JsonNode> results, Map<String, Object> op) {
        return results.get((String) op.get("operationId"));
    }

    private List<JsonNode> pullExpenses(String token) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + token);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            MvcResult r = mockMvc.perform(request).andReturn();
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
            JsonNode data = objectMapper.readTree(body(r)).path("data");
            data.path("entities").path("expenses").forEach(rows::add);
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    private void moduleRow(String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'expenses', ?, ?) "
                        + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status",
                tenantId, status, "ACTIVE".equals(status) ? "PREMIUM" : "FREE");
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private JsonNode search(String text) throws Exception {
        return ok(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/expenses")
                .header("Authorization", "Bearer " + ownerToken).param("search", text)).andReturn(), 200).path("data");
    }

    private boolean live(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived_at IS NULL FROM \"" + schema
                + "\".expenses WHERE id = ?", Boolean.class, UUID.fromString(id)));
    }

    /** The member of {@code token} gets an owner-defined MANAGER role with the three expense rights (19.14). */
    private void grantExpenses(String token) {
        UUID user = UUID.fromString(jwtTokenProvider.parseToken(token).getSubject());
        UUID role = UUID.randomUUID();
        jdbc.update("INSERT INTO public.tenant_roles (id, tenant_id, name, base_role, permissions) VALUES (?, ?, "
                + "'Gérant dépenses', 'MANAGER', ARRAY['EXPENSE_VIEW','EXPENSE_CREATE','EXPENSE_DELETE'])", role,
                tenantId);
        jdbc.update("UPDATE public.user_tenant_memberships SET custom_role_id = ? WHERE tenant_id = ? AND user_id = ?",
                role, tenantId, user);
    }

    private String memberToken(String role, String firstName, UUID store) {
        UUID memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Ngo', false)", memberId, store, firstName);
        return TestAccess.token(jdbc, jwtTokenProvider, memberId, schema, role, "ACTIVE", store, false);
    }

    // ── REST ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner records an expense: table provisioned on first use, oldest store, today (UTC), French labels; "
            + "the same id sent again → the same expense, one row")
    void createAndReplay() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> body = expense(150000, "rent", null);
        body.put("id", id);
        body.put("description", "  Loyer de novembre ");
        body.put("paymentMethod", "mobile_money");
        JsonNode created = create(ownerToken, body);
        assertThat(created.path("id").asText()).isEqualTo(id);
        assertThat(created.path("storeId").asText()).isEqualTo(storeA.toString());
        assertThat(created.path("expenseDate").asText()).isEqualTo(today());
        assertThat(created.path("amount").asText()).isEqualTo("150000");
        assertThat(created.path("categoryLabel").asText()).isEqualTo("Loyer");
        assertThat(created.path("label").asText()).isEqualTo("Loyer de novembre");
        assertThat(created.path("paymentMethodLabel").asText()).isEqualTo("Mobile money");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"" + schema + "\".expenses")).isTrue();

        assertThat(created.path("storeName").asText()).isNotBlank();
        body.put("amount", 1);
        JsonNode again = ok(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", body), 200).path("data"); // replay
        assertThat(again.path("amount").asText()).isEqualTo("150000");
        assertThat(rows()).isEqualTo(1);

        JsonNode got = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses/" + id, null), 200).path("data");
        assertThat(got.path("category").asText()).isEqualTo("rent");
        JsonNode options = ok(call(managerToken, HttpMethod.GET, "/api/v1/expenses/options", null), 200).path("data");
        assertThat(options.path("categories")).hasSize(10);
        assertThat(options.path("paymentMethods")).hasSize(5);
    }

    @Test
    @DisplayName("Bref rules refused with 422 VALIDATION_ERROR and a French message; nothing written")
    void validation() throws Exception {
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", expense(1000, "food", null)), 422,
                "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", expense(10.123, "other", null)), 422,
                "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", expense(0, "other", null)), 422,
                "VALIDATION_ERROR");
        MvcResult future = call(ownerToken, HttpMethod.POST, "/api/v1/expenses",
                expense(1000, "other", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString()));
        error(future, 422, "VALIDATION_ERROR");
        assertThat(objectMapper.readTree(body(future)).path("error").asText())
                .isEqualTo("La date de la dépense ne peut pas dépasser demain");
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", expense(1000, "other", "2026-02-31")), 422,
                "VALIDATION_ERROR");
        Map<String, Object> elsewhere = expense(1000, "other", null);
        elsewhere.put("storeId", UUID.randomUUID().toString());
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", elsewhere), 422, "VALIDATION_ERROR");
        boolean table = Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "\"" + schema + "\".expenses"));
        assertThat(!table || rows() == 0).isTrue();
    }

    @Test
    @DisplayName("store scope: the manager records and sees only their store; the owner sees all or filters; another "
            + "store's expense is 404 for the manager, who edits and deletes in their own store")
    void storeScope() throws Exception {
        Map<String, Object> inA = expense(5000, "transport", null);
        inA.put("storeId", storeA.toString());
        String a = create(ownerToken, inA).path("id").asText();
        JsonNode b = create(managerToken, expense(7000, "telecom", null));
        assertThat(b.path("storeId").asText()).isEqualTo(storeB.toString());

        Map<String, Object> intoA = expense(100, "other", null);
        intoA.put("storeId", storeA.toString());
        error(call(managerToken, HttpMethod.POST, "/api/v1/expenses", intoA), 403, "FORBIDDEN");

        JsonNode managerList = ok(call(managerToken, HttpMethod.GET, "/api/v1/expenses?storeId=" + storeA, null),
                200);
        assertThat(managerList.path("data")).hasSize(1);
        assertThat(managerList.path("data").get(0).path("storeId").asText()).isEqualTo(storeB.toString());
        assertThat(managerList.path("meta").path("totalAmount").asText()).isEqualTo("7000");

        JsonNode all = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses", null), 200);
        assertThat(all.path("data")).hasSize(2);
        assertThat(all.path("meta").path("count").asLong()).isEqualTo(2);
        assertThat(all.path("meta").path("totalAmount").asText()).isEqualTo("12000");
        JsonNode onlyA = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?storeId=" + storeA, null), 200);
        assertThat(onlyA.path("data")).hasSize(1);
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?storeId=" + UUID.randomUUID(), null), 400,
                "VALIDATION_ERROR");

        error(call(managerToken, HttpMethod.GET, "/api/v1/expenses/" + a, null), 404, "EXPENSE_NOT_FOUND");
        error(call(managerToken, HttpMethod.PUT, "/api/v1/expenses/" + a, expense(1, "other", null)), 404,
                "EXPENSE_NOT_FOUND");
        error(call(managerToken, HttpMethod.DELETE, "/api/v1/expenses/" + a, null), 404, "EXPENSE_NOT_FOUND");
        assertThat(live(a)).isTrue();

        String bId = b.path("id").asText();
        Map<String, Object> moveToA = expense(8000, "telecom", null);
        moveToA.put("storeId", storeA.toString());
        error(call(managerToken, HttpMethod.PUT, "/api/v1/expenses/" + bId, moveToA), 403, "FORBIDDEN");
        JsonNode edited = ok(call(managerToken, HttpMethod.PUT, "/api/v1/expenses/" + bId,
                expense(8000, "telecom", null)), 200).path("data");
        assertThat(edited.path("amount").asText()).isEqualTo("8000");
        assertThat(edited.path("storeId").asText()).isEqualTo(storeB.toString());
        ok(call(managerToken, HttpMethod.DELETE, "/api/v1/expenses/" + bId, null), 204);
        assertThat(live(bId)).isFalse();
    }

    @Test
    @DisplayName("keyset list: every sort walks all pages without gap or repeat; filters; totals on the first page only")
    void keysetList() throws Exception {
        int[] amounts = {3000, 1000, 5000, 1000, 2000};
        String[] dates = {"2026-10-01", "2026-10-03", "2026-10-02", "2026-10-05", "2026-10-04"};
        String[] notes = {"Facture SONEL 500 francs", "Code a_b", "Facture SONEL 50%", "Chemin c:\\caisse", "axb"};
        for (int i = 0; i < amounts.length; i++) {
            Map<String, Object> e = expense(amounts[i], i % 2 == 0 ? "rent" : "salaries", dates[i]);
            e.put("note", notes[i]);
            create(ownerToken, e);
        }
        for (String sort : List.of("recent", "oldest", "amount_desc", "amount_asc", "bogus")) {
            List<JsonNode> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                String url = "/api/v1/expenses?size=2&sort=" + sort + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode page = ok(call(ownerToken, HttpMethod.GET, url, null), 200);
                page.path("data").forEach(seen::add);
                JsonNode meta = page.path("meta");
                assertThat(meta.has("totalAmount")).as(sort + " page " + pages).isEqualTo(pages == 0);
                if (pages == 0) {
                    assertThat(meta.path("count").asLong()).isEqualTo(5);
                    assertThat(meta.path("totalAmount").asText()).isEqualTo("12000");
                }
                cursor = meta.path("hasMore").asBoolean() ? meta.path("nextCursor").asText() : null;
                pages++;
            } while (cursor != null);
            assertThat(pages).as(sort).isEqualTo(3);
            assertThat(seen.stream().map(n -> n.path("id").asText()).distinct()).as(sort).hasSize(5);
            List<String> order = seen.stream().map(n -> n.path("expenseDate").asText()).toList();
            switch (sort) {
                case "recent", "bogus" -> assertThat(order).containsExactly("2026-10-05", "2026-10-04", "2026-10-03",
                        "2026-10-02", "2026-10-01");
                case "oldest" -> assertThat(order).containsExactly("2026-10-01", "2026-10-02", "2026-10-03",
                        "2026-10-04", "2026-10-05");
                case "amount_desc" -> assertThat(seen.stream().map(n -> n.path("amount").asInt()))
                        .containsExactly(5000, 3000, 2000, 1000, 1000);
                case "amount_asc" -> assertThat(seen.stream().map(n -> n.path("amount").asInt()))
                        .containsExactly(1000, 1000, 2000, 3000, 5000);
                default -> throw new IllegalStateException(sort);
            }
        }
        JsonNode filtered = ok(call(ownerToken, HttpMethod.GET,
                "/api/v1/expenses?category=rent&from=2026-10-02&to=2026-10-04", null), 200);
        assertThat(filtered.path("data").findValuesAsText("expenseDate")).containsExactly("2026-10-04", "2026-10-02");
        assertThat(filtered.path("meta").path("totalAmount").asText()).isEqualTo("7000");
        // LIKE wildcards are literal: « 50% » is not « 500 », « a_b » is not « axb », a backslash is a backslash
        assertThat(search("sonel 50%").findValuesAsText("note")).containsExactly("Facture SONEL 50%");
        assertThat(search("a_b").findValuesAsText("note")).containsExactly("Code a_b");
        assertThat(search("c:\\caisse").findValuesAsText("note")).containsExactly("Chemin c:\\caisse");

        // a cursor is bound to its filters and sort: reused after a filter change → 400
        String next = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?size=2", null), 200)
                .path("meta").path("nextCursor").asText();
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?size=2&category=rent&cursor=" + next, null), 400,
                "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?size=2&sort=oldest&cursor=" + next, null), 400,
                "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?category=food", null), 400, "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?cursor=xyz", null), 400, "VALIDATION_ERROR");
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?from=2026-10-05&to=2026-10-01", null), 400,
                "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("edit replaces the content (stale expectedUpdatedAt → 409); delete is final and idempotent")
    void editAndDelete() throws Exception {
        Map<String, Object> body = expense(1000, "other", "2026-10-01");
        body.put("label", "Divers");
        JsonNode created = create(ownerToken, body);
        String id = created.path("id").asText();

        Map<String, Object> stale = expense(2000, "taxes", "2026-10-02");
        stale.put("expectedUpdatedAt", "2020-01-01T00:00:00Z");
        error(call(ownerToken, HttpMethod.PUT, "/api/v1/expenses/" + id, stale), 409, "OPTIMISTIC_LOCK");

        Map<String, Object> edit = expense(2000, "taxes", "2026-10-02");
        edit.put("expectedUpdatedAt", created.path("updatedAt").asText());
        JsonNode updated = ok(call(ownerToken, HttpMethod.PUT, "/api/v1/expenses/" + id, edit), 200).path("data");
        assertThat(updated.path("category").asText()).isEqualTo("taxes");
        assertThat(updated.path("label").isNull()).isTrue(); // absent = cleared
        assertThat(updated.path("storeId").asText()).isEqualTo(storeA.toString()); // absent = unchanged

        ok(call(ownerToken, HttpMethod.DELETE, "/api/v1/expenses/" + id, null), 204);
        ok(call(ownerToken, HttpMethod.DELETE, "/api/v1/expenses/" + id, null), 204);
        error(call(ownerToken, HttpMethod.GET, "/api/v1/expenses/" + id, null), 404, "EXPENSE_NOT_FOUND");
        error(call(ownerToken, HttpMethod.PUT, "/api/v1/expenses/" + id, edit), 404, "EXPENSE_NOT_FOUND");
        error(call(ownerToken, HttpMethod.DELETE, "/api/v1/expenses/" + UUID.randomUUID(), null), 404,
                "EXPENSE_NOT_FOUND");
        assertThat(ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses", null), 200).path("data")).isEmpty();
    }

    // ── sync ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sync: the same batch pushed twice → DUPLICATE, one row; an older edit is superseded (LWW); a deletion "
            + "reaches the pull as a tombstone")
    void syncDuplicateLwwTombstone() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> createPayload = expense(150000, "rent", "2026-11-01");
        createPayload.put("id", id);
        var create = op("CREATE_EXPENSE", id, createPayload);
        Map<String, Object> older = expense(111, "rent", "2026-11-01");
        older.put("expenseId", id);
        var olderEdit = op("UPDATE_EXPENSE", id, older); // queued first: older clientTimestamp
        Map<String, Object> newer = expense(155000, "rent", "2026-11-01");
        newer.put("expenseId", id);
        var newerEdit = op("UPDATE_EXPENSE", id, newer);

        var first = push(ownerToken, create, newerEdit);
        assertThat(result(first, create).path("status").asText()).isEqualTo("APPLIED");
        assertThat(result(first, newerEdit).path("status").asText()).isEqualTo("APPLIED");
        var again = push(ownerToken, create, newerEdit);
        assertThat(result(again, create).path("status").asText()).isEqualTo("DUPLICATE");
        assertThat(result(again, newerEdit).path("status").asText()).isEqualTo("DUPLICATE");
        assertThat(rows()).isEqualTo(1);

        var late = push(ownerToken, olderEdit);
        assertThat(result(late, olderEdit).path("status").asText()).isEqualTo("APPLIED");
        assertThat(result(late, olderEdit).path("reason").asText()).isEqualTo("LWW_SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT amount FROM \"" + schema + "\".expenses WHERE id = ?",
                java.math.BigDecimal.class, UUID.fromString(id))).isEqualByComparingTo("155000");

        JsonNode pulled = pullExpenses(ownerToken).stream().filter(n -> n.path("id").asText().equals(id))
                .findFirst().orElseThrow();
        assertThat(pulled.path("amount").asText()).isEqualTo("155000");
        assertThat(pulled.path("categoryLabel").asText()).isEqualTo("Loyer");

        var delete = op("DELETE_EXPENSE", id, Map.of("expenseId", id));
        assertThat(result(push(ownerToken, delete), delete).path("status").asText()).isEqualTo("APPLIED");
        JsonNode tombstone = pullExpenses(ownerToken).stream().filter(n -> n.path("id").asText().equals(id))
                .findFirst().orElseThrow();
        assertThat(tombstone.path("deleted").asBoolean()).isTrue();
        assertThat(tombstone.has("amount")).isFalse();
    }

    @Test
    @DisplayName("sync: a manager's operations apply in their store and their pull carries their store's expenses only "
            + "(a deletion as a tombstone); an invalid payload is REJECTED VALIDATION_ERROR")
    void syncScope() throws Exception {
        Map<String, Object> inA = expense(5000, "transport", null);
        inA.put("storeId", storeA.toString());
        String a = create(ownerToken, inA).path("id").asText();
        String b = UUID.randomUUID().toString();
        Map<String, Object> payload = expense(7000, "telecom", null);
        payload.put("id", b);
        var create = op("CREATE_EXPENSE", b, payload);
        var delete = op("DELETE_EXPENSE", b, Map.of("expenseId", b));
        Map<String, Object> bad = expense(10, "food", null);
        bad.put("id", UUID.randomUUID().toString());
        var invalid = op("CREATE_EXPENSE", (String) bad.get("id"), bad);
        var results = push(managerToken, create, invalid);
        assertThat(result(results, create).path("status").asText()).isEqualTo("APPLIED");
        assertThat(result(results, invalid).path("reason").asText()).isEqualTo("VALIDATION_ERROR");

        List<String> managerSees = pullExpenses(managerToken).stream().map(n -> n.path("id").asText()).toList();
        assertThat(managerSees).containsExactly(b);
        assertThat(pullExpenses(ownerToken).stream().map(n -> n.path("id").asText())).containsExactlyInAnyOrder(a, b);

        assertThat(result(push(managerToken, delete), delete).path("status").asText()).isEqualTo("APPLIED");
        List<JsonNode> afterDelete = pullExpenses(managerToken);
        assertThat(afterDelete).hasSize(1);
        assertThat(afterDelete.get(0).path("deleted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("module DISABLED: REST refused, push REJECTED MODULE_INACTIVE, pull skips expenses")
    void moduleDisabled() throws Exception {
        create(ownerToken, expense(1000, "other", null));
        moduleRow("DISABLED");
        assertThat(call(ownerToken, HttpMethod.GET, "/api/v1/expenses", null).getResponse().getStatus())
                .isEqualTo(403);
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = expense(1000, "other", null);
        payload.put("id", id);
        var create = op("CREATE_EXPENSE", id, payload);
        assertThat(result(push(ownerToken, create), create).path("reason").asText()).isEqualTo("MODULE_INACTIVE");
        assertThat(pullExpenses(ownerToken)).isEmpty();
    }

    // ── review 20-10 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("edit / delete pushed before the tenant has the expenses table → REJECTED EXPENSE_NOT_FOUND (French "
            + "message), the operation's transaction never aborted")
    void syncBeforeTable() throws Exception {
        // the registration trial provisions the module: back to a tenant that never used it (table and history gone)
        jdbc.execute("DROP TABLE IF EXISTS \"" + schema + "\".expenses");
        jdbc.execute("DROP TABLE IF EXISTS \"" + schema + "\".flyway_history_expenses");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, "\"" + schema + "\".expenses"))
                .isTrue();
        String id = UUID.randomUUID().toString();
        Map<String, Object> edit = expense(10, "other", null);
        edit.put("expenseId", id);
        var update = op("UPDATE_EXPENSE", id, edit);
        var delete = op("DELETE_EXPENSE", id, Map.of("expenseId", id));
        var results = push(ownerToken, update, delete);
        for (var op : List.of(update, delete)) {
            assertThat(result(results, op).path("status").asText()).isEqualTo("REJECTED");
            assertThat(result(results, op).path("reason").asText()).isEqualTo("EXPENSE_NOT_FOUND");
            assertThat(result(results, op).path("conflictData").path("message").asText())
                    .isEqualTo("Dépense introuvable");
        }
    }

    @Test
    @DisplayName("offline creation without date: the day it was queued (Africa/Douala); a REST edit without date "
            + "keeps the current date")
    void offlineDates() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = expense(1000, "other", null);
        payload.put("id", id);
        var create = op("CREATE_EXPENSE", id, payload);
        Instant queued = Instant.parse((String) create.get("clientTimestamp"));
        assertThat(result(push(ownerToken, create), create).path("status").asText()).isEqualTo("APPLIED");
        String expected = LocalDate.ofInstant(queued, java.time.ZoneId.of("Africa/Douala")).toString();
        JsonNode got = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses/" + id, null), 200).path("data");
        assertThat(got.path("expenseDate").asText()).isEqualTo(expected);

        String rest = create(ownerToken, expense(500, "rent", "2026-10-01")).path("id").asText();
        JsonNode edited = ok(call(ownerToken, HttpMethod.PUT, "/api/v1/expenses/" + rest, expense(600, "rent", null)),
                200).path("data");
        assertThat(edited.path("expenseDate").asText()).isEqualTo("2026-10-01");
        assertThat(edited.path("amount").asText()).isEqualTo("600");
    }

    @Test
    @DisplayName("a manager of store B replaying store A's expense id → 403 with a French message, nothing of the "
            + "expense disclosed; deleting it → 404, the row stays live")
    void otherStoreIds() throws Exception {
        Map<String, Object> inA = expense(5000, "transport", null);
        inA.put("storeId", storeA.toString());
        inA.put("label", "Taxi secret");
        String a = create(ownerToken, inA).path("id").asText();

        Map<String, Object> replay = expense(1, "other", null);
        replay.put("id", a);
        MvcResult r = call(managerToken, HttpMethod.POST, "/api/v1/expenses", replay);
        error(r, 403, "FORBIDDEN");
        assertThat(body(r)).doesNotContain("Taxi secret").doesNotContain("\"amount\"").doesNotContain("\"data\"").doesNotContain(storeA.toString())
                .contains("autre boutique");

        error(call(managerToken, HttpMethod.DELETE, "/api/v1/expenses/" + a, null), 404, "EXPENSE_NOT_FOUND");
        assertThat(live(a)).isTrue();
    }

    @Test
    @DisplayName("replaying a deleted expense id → 404 over REST, REJECTED EXPENSE_NOT_FOUND over sync")
    void replayDeleted() throws Exception {
        Map<String, Object> body = expense(1000, "other", null);
        String id = create(ownerToken, body).path("id").asText();
        ok(call(ownerToken, HttpMethod.DELETE, "/api/v1/expenses/" + id, null), 204);
        body.put("id", id);
        error(call(ownerToken, HttpMethod.POST, "/api/v1/expenses", body), 404, "EXPENSE_NOT_FOUND");
        var create = op("CREATE_EXPENSE", id, body);
        JsonNode result = result(push(ownerToken, create), create);
        assertThat(result.path("status").asText()).isEqualTo("REJECTED");
        assertThat(result.path("reason").asText()).isEqualTo("EXPENSE_NOT_FOUND");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("a DELETE_EXPENSE queued before the last edit is superseded (LWW): the expense stays live")
    void deleteSuperseded() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = expense(1000, "other", null);
        payload.put("id", id);
        var create = op("CREATE_EXPENSE", id, payload);
        var olderDelete = op("DELETE_EXPENSE", id, Map.of("expenseId", id));
        Map<String, Object> edit = expense(2000, "other", null);
        edit.put("expenseId", id);
        var newerEdit = op("UPDATE_EXPENSE", id, edit);
        var first = push(ownerToken, create, newerEdit);
        assertThat(result(first, newerEdit).path("status").asText()).isEqualTo("APPLIED");
        JsonNode late = result(push(ownerToken, olderDelete), olderDelete);
        assertThat(late.path("status").asText()).isEqualTo("APPLIED");
        assertThat(late.path("reason").asText()).isEqualTo("LWW_SUPERSEDED");
        assertThat(live(id)).isTrue();
    }

    @Test
    @DisplayName("the owner moves an expense to another store with PUT; the response names the new store")
    void ownerMoves() throws Exception {
        String id = create(ownerToken, expense(1000, "other", null)).path("id").asText();
        Map<String, Object> move = expense(1000, "other", null);
        move.put("storeId", storeB.toString());
        JsonNode moved = ok(call(ownerToken, HttpMethod.PUT, "/api/v1/expenses/" + id, move), 200).path("data");
        assertThat(moved.path("storeId").asText()).isEqualTo(storeB.toString());
        assertThat(moved.path("storeName").asText()).isEqualTo("Boutique Bonapriso");
        assertThat(ok(call(managerToken, HttpMethod.GET, "/api/v1/expenses/" + id, null), 200)
                .path("data").path("id").asText()).isEqualTo(id);
    }

    // ── user decision 2026-11-11: expenses for the OWNER only by default (a custom role may grant them) ──

    @Test
    @DisplayName("an EMPLOYEE, a VIEWER or a built-in MANAGER (reprise 11/11/2026) is refused on every REST route "
            + "(403 FORBIDDEN, French message), every sync operation is REJECTED FORBIDDEN (never superseded) and "
            + "their pull carries no expense")
    void employeeAndViewerRefused() throws Exception {
        Map<String, Object> inB = expense(5000, "transport", null);
        inB.put("storeId", storeB.toString());
        String b = create(ownerToken, inB).path("id").asText();
        for (String token : List.of(employeeToken, viewerToken, plainManagerToken)) {
            List<MvcResult> refused = List.of(
                    call(token, HttpMethod.GET, "/api/v1/expenses", null),
                    call(token, HttpMethod.GET, "/api/v1/expenses/options", null),
                    call(token, HttpMethod.GET, "/api/v1/expenses/" + b, null),
                    call(token, HttpMethod.POST, "/api/v1/expenses", expense(100, "other", null)),
                    call(token, HttpMethod.PUT, "/api/v1/expenses/" + b, expense(100, "other", null)),
                    call(token, HttpMethod.DELETE, "/api/v1/expenses/" + b, null));
            for (MvcResult r : refused) {
                error(r, 403, "FORBIDDEN");
                assertThat(objectMapper.readTree(body(r)).path("error").asText()).isNotBlank();
                assertThat(body(r)).doesNotContain("transport").doesNotContain("5000");
            }

            String id = UUID.randomUUID().toString();
            Map<String, Object> payload = expense(100, "other", null);
            payload.put("id", id);
            var create = op("CREATE_EXPENSE", id, payload);
            Map<String, Object> edit = expense(1, "other", null);
            edit.put("expenseId", b);
            var update = op("UPDATE_EXPENSE", b, edit); // queued before the owner's REST write: would be superseded
            var delete = op("DELETE_EXPENSE", b, Map.of("expenseId", b));
            var results = push(token, create, update, delete);
            for (var op : List.of(create, update, delete)) {
                assertThat(result(results, op).path("status").asText()).isEqualTo("REJECTED");
                assertThat(result(results, op).path("reason").asText()).isEqualTo("FORBIDDEN");
                if (!token.equals(viewerToken)) {
                    // a VIEWER's batch is refused as a whole before any handler (Story 19.5: read-only role)
                    assertThat(result(results, op).path("conflictData").path("message").asText())
                            .isEqualTo("Les dépenses sont réservées au propriétaire : demandez-lui le droit de "
                                    + "les gérer.");
                }
            }
            assertThat(pullExpenses(token)).isEmpty();
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(live(b)).isTrue();
        assertThat(jdbc.queryForObject("SELECT amount FROM \"" + schema + "\".expenses WHERE id = ?",
                java.math.BigDecimal.class, UUID.fromString(b))).isEqualByComparingTo("5000");
        assertThat(pullExpenses(managerToken).stream().map(n -> n.path("id").asText())).containsExactly(b);
    }

    @Test
    @DisplayName("same date: the creation time then the id break the ties — every page walked, nothing repeated")
    void dateTies() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(create(ownerToken, expense(100 + i, "other", "2026-10-10")).path("id").asText());
        }
        for (String sort : List.of("recent", "oldest")) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            do {
                JsonNode page = ok(call(ownerToken, HttpMethod.GET, "/api/v1/expenses?size=1&sort=" + sort
                        + (cursor == null ? "" : "&cursor=" + cursor), null), 200);
                page.path("data").forEach(n -> seen.add(n.path("id").asText()));
                cursor = page.path("meta").path("hasMore").asBoolean() ? page.path("meta").path("nextCursor").asText()
                        : null;
            } while (cursor != null);
            List<String> expected = new ArrayList<>(created); // created one after the other: creation-time order
            if (sort.equals("recent")) {
                java.util.Collections.reverse(expected);
            }
            assertThat(seen).as(sort).containsExactlyElementsOf(expected);
        }
    }
}
