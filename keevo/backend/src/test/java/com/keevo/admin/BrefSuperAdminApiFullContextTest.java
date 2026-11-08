package com.keevo.admin;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 20.13 — the super-admin API inherited from Bref, end to end with the production security chain: platform
 * dashboard, cross-account customers / products / expenses (list keyset across two accounts + detail), platform users
 * (list, stats, detail, devices). Every route answers 403 to an owner. The shared test database holds other tests'
 * tenants: assertions target the data seeded here (account filter, unique search marker, lower bounds).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Super-admin API inherited from Bref (Story 20.13)")
class BrefSuperAdminApiFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final UUID adminId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final String marker = "zq" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private final List<UUID> users = new ArrayList<>();

    private String schemaA;
    private String schemaB;
    private UUID tenantA;
    private UUID tenantB;
    private UUID storeA;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        schemaA = tx.execute(s -> tenantFactory.create()).getSchemaName();
        schemaB = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantA = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schemaA);
        tenantB = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schemaB);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schemaA + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        adminToken = TestAccess.token(jdbc, jwtTokenProvider, adminId, "public", "SUPER_ADMIN");
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schemaA, "OWNER");
        TestAccess.grant(jdbc, ownerId, schemaB, "OWNER");
        user(ownerId, "Awa " + marker, "OWNER");
    }

    @AfterEach
    void tearDown() {
        for (UUID u : users) {
            jdbc.update("DELETE FROM public.refresh_tokens WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", u);
            jdbc.update("DELETE FROM public.users WHERE id = ?", u);
        }
        jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id = ?", ownerId);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── security ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/dashboard/overview", "/api/v1/admin/dashboard/growth",
            "/api/v1/admin/dashboard/top-accounts", "/api/v1/admin/dashboard/activity", "/api/v1/admin/customers",
            "/api/v1/admin/products", "/api/v1/admin/expenses", "/api/v1/admin/users", "/api/v1/admin/users/stats",
            "/api/v1/admin/users/00000000-0000-0000-0000-000000000001",
            "/api/v1/admin/users/00000000-0000-0000-0000-000000000001/devices",
            "/api/v1/admin/tenants/00000000-0000-0000-0000-000000000001/customers/00000000-0000-0000-0000-000000000002",
            "/api/v1/admin/tenants/00000000-0000-0000-0000-000000000001/products/00000000-0000-0000-0000-000000000002",
            "/api/v1/admin/tenants/00000000-0000-0000-0000-000000000001/expenses/00000000-0000-0000-0000-000000000002"})
    @DisplayName("every new route refuses a non-super-admin (owner) with 403")
    void ownerRefused(String path) throws Exception {
        assertThat(call(path, ownerToken).getResponse().getStatus()).isEqualTo(403);
    }

    // ── dashboard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dashboard: overview, growth, top accounts (our sale) and activity (our new account)")
    void dashboard() throws Exception {
        sale(schemaA, storeA, 7_654_321, Instant.now().minus(1, ChronoUnit.MINUTES));

        JsonNode overview = data(call("/api/v1/admin/dashboard/overview", adminToken));
        assertThat(overview.path("revenue").path("total").asLong()).isGreaterThanOrEqualTo(7_654_321);
        assertThat(overview.path("accounts").path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(overview.path("users").path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(overview.path("stores").path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(overview.has("documents")).isTrue();

        JsonNode growth = data(call("/api/v1/admin/dashboard/growth?months=3", adminToken));
        assertThat(growth).hasSize(3);
        assertThat(growth.get(2).path("revenue").asLong()).isGreaterThanOrEqualTo(7_654_321);
        assertThat(growth.get(2).path("newAccounts").asLong()).isGreaterThanOrEqualTo(2);

        JsonNode top = data(call("/api/v1/admin/dashboard/top-accounts?limit=20&days=1", adminToken));
        JsonNode ours = find(top, "tenantId", tenantA.toString());
        assertThat(ours).as(top.toString()).isNotNull();
        assertThat(ours.path("revenue").asLong()).isEqualTo(7_654_321);
        assertThat(ours.path("ownerName").asText()).isEqualTo("Awa " + marker);
        assertThat(ours.path("statusLabel").asText()).isEqualTo("Actif");
        assertThat(ours.path("storeCount").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode activity = data(call("/api/v1/admin/dashboard/activity?limit=50", adminToken));
        assertThat(activity).anySatisfy(e -> {
            assertThat(e.path("type").asText()).isEqualTo("ACCOUNT_CREATED");
            assertThat(e.path("label").asText()).isEqualTo("Nouveau compte");
            assertThat(e.path("tenantId").asText()).isEqualTo(tenantB.toString());
        });

        error(call("/api/v1/admin/dashboard/growth?months=99", adminToken), 422);
    }

    // ── cross-account lists ──────────────────────────────────────────────────

    @Test
    @DisplayName("customers: keyset across two accounts (no duplicate), account filter, detail, 404, bad cursor")
    void customers() throws Exception {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID c1 = client(schemaA, storeA, "Cliente " + marker + " un", t);
        UUID c2 = client(schemaB, null, "Cliente " + marker + " deux", t.plusSeconds(10));
        UUID c3 = client(schemaA, storeA, "Cliente " + marker + " trois", t.plusSeconds(20));

        JsonNode page1 = data(call("/api/v1/admin/customers?q=" + marker + "&limit=2", adminToken));
        assertThat(ids(page1.path("items"))).containsExactly(c3.toString(), c2.toString());
        assertThat(page1.path("hasMore").asBoolean()).isTrue();
        assertThat(page1.path("items").get(0).path("tenantName").asText()).isNotBlank();
        assertThat(page1.path("items").get(0).path("storeName").asText()).isNotBlank();
        String cursor = page1.path("nextCursor").asText();
        JsonNode page2 = data(call("/api/v1/admin/customers?q=" + marker + "&limit=2&cursor=" + cursor, adminToken));
        assertThat(ids(page2.path("items"))).containsExactly(c1.toString());
        assertThat(page2.path("nextCursor").isNull()).isTrue();

        JsonNode onlyB = data(call("/api/v1/admin/customers?q=" + marker + "&tenantId=" + tenantB, adminToken));
        assertThat(ids(onlyB.path("items"))).containsExactly(c2.toString());

        JsonNode detail = data(call("/api/v1/admin/tenants/" + tenantA + "/customers/" + c1, adminToken));
        assertThat(detail.path("name").asText()).isEqualTo("Cliente " + marker + " un");
        assertThat(detail.path("tenantId").asText()).isEqualTo(tenantA.toString());

        error(call("/api/v1/admin/tenants/" + tenantB + "/customers/" + c1, adminToken), 404);
        error(call("/api/v1/admin/tenants/" + UUID.randomUUID() + "/customers/" + c1, adminToken), 404);
        error(call("/api/v1/admin/customers?q=autre&cursor=" + cursor, adminToken), 422);
        error(call("/api/v1/admin/customers?tenantId=pas-un-compte", adminToken), 422);
    }

    @Test
    @DisplayName("products: active / archived lists across accounts, detail with French state label")
    void products() throws Exception {
        UUID p1 = product(schemaA, "Riz " + marker, false);
        UUID p2 = product(schemaB, "Huile " + marker, true);

        JsonNode active = data(call("/api/v1/admin/products?q=" + marker, adminToken));
        assertThat(ids(active.path("items"))).containsExactly(p1.toString());
        assertThat(active.path("items").get(0).path("statusLabel").asText()).isEqualTo("Actif");
        JsonNode archived = data(call("/api/v1/admin/products?q=" + marker + "&status=archived", adminToken));
        assertThat(ids(archived.path("items"))).containsExactly(p2.toString());

        JsonNode detail = data(call("/api/v1/admin/tenants/" + tenantB + "/products/" + p2, adminToken));
        assertThat(detail.path("statusLabel").asText()).isEqualTo("Archivé");
        error(call("/api/v1/admin/products?status=supprime", adminToken), 422);
        error(call("/api/v1/admin/tenants/" + tenantA + "/products/" + p2, adminToken), 404);
    }

    @Test
    @DisplayName("expenses: list of an account with labels and total, filters, detail with author, 404")
    void expenses() throws Exception {
        String e1 = createExpense(15_000, "rent", "Loyer " + marker);
        createExpense(2_500, "transport", "Taxi " + marker);

        JsonNode page = data(call("/api/v1/admin/expenses?tenantId=" + tenantA + "&q=" + marker, adminToken));
        assertThat(page.path("page").path("items")).hasSize(2);
        assertThat(page.path("totalAmount").decimalValue()).isEqualByComparingTo("17500");
        JsonNode rent = data(call("/api/v1/admin/expenses?tenantId=" + tenantA + "&q=" + marker + "&category=rent",
                adminToken));
        assertThat(ids(rent.path("page").path("items"))).containsExactly(e1);
        assertThat(rent.path("page").path("items").get(0).path("categoryLabel").asText()).isEqualTo("Loyer");
        assertThat(rent.path("totalAmount").decimalValue()).isEqualByComparingTo("15000");

        JsonNode detail = data(call("/api/v1/admin/tenants/" + tenantA + "/expenses/" + e1, adminToken));
        assertThat(detail.path("categoryLabel").asText()).isEqualTo("Loyer");
        assertThat(detail.path("createdByName").asText()).isEqualTo("Awa " + marker);
        assertThat(detail.path("storeName").asText()).isNotBlank();

        // Account B never used the module: no expenses table, empty list, no error.
        JsonNode none = data(call("/api/v1/admin/expenses?tenantId=" + tenantB, adminToken));
        assertThat(none.path("page").path("items")).isEmpty();
        error(call("/api/v1/admin/tenants/" + tenantB + "/expenses/" + e1, adminToken), 404);
        error(call("/api/v1/admin/expenses?category=food", adminToken), 422);
        error(call("/api/v1/admin/expenses?from=2026-11-09&to=2026-11-01", adminToken), 422);
    }

    // ── users ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("users: list (one person, two accounts), role filter, stats, detail, devices, 404")
    void usersApi() throws Exception {
        UUID employee = UUID.randomUUID();
        user(employee, "Paul " + marker, "EMPLOYEE");
        TestAccess.grant(jdbc, employee, schemaB, "EMPLOYEE");
        session(ownerId, schemaA, "Redmi Note 12 · Android 14");
        session(ownerId, schemaB, null);

        JsonNode list = data(call("/api/v1/admin/users?q=" + marker, adminToken));
        assertThat(list.path("items")).hasSize(2);
        JsonNode owner = find(list.path("items"), "id", ownerId.toString());
        assertThat(owner.path("memberships")).hasSize(2);
        assertThat(owner.path("memberships").get(0).path("roleLabel").asText()).isEqualTo("Propriétaire");
        assertThat(owner.path("deviceCount").asLong()).isEqualTo(2);

        JsonNode owners = data(call("/api/v1/admin/users?q=" + marker + "&role=OWNER", adminToken));
        assertThat(ids(owners.path("items"))).containsExactly(ownerId.toString());
        JsonNode paged = data(call("/api/v1/admin/users?q=" + marker + "&limit=1", adminToken));
        assertThat(paged.path("items")).hasSize(1);
        JsonNode next = data(call("/api/v1/admin/users?q=" + marker + "&limit=1&cursor="
                + paged.path("nextCursor").asText(), adminToken));
        assertThat(ids(next.path("items"))).doesNotContainAnyElementsOf(ids(paged.path("items"))).hasSize(1);

        JsonNode stats = data(call("/api/v1/admin/users/stats", adminToken));
        assertThat(stats.path("totalUsers").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(stats.path("newUsers").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(stats.path("byRole")).anySatisfy(r -> assertThat(r.path("label").asText()).isEqualTo("Employé"));

        JsonNode detail = data(call("/api/v1/admin/users/" + ownerId, adminToken));
        assertThat(detail.path("displayName").asText()).isEqualTo("Awa " + marker);
        JsonNode devices = data(call("/api/v1/admin/users/" + ownerId + "/devices", adminToken));
        assertThat(devices).hasSize(2);
        assertThat(devices).anySatisfy(d -> assertThat(d.path("deviceLabel").asText()).isEqualTo("Appareil inconnu"));
        assertThat(devices).anySatisfy(d -> assertThat(d.path("tenantId").asText()).isEqualTo(tenantA.toString()));

        error(call("/api/v1/admin/users/" + UUID.randomUUID(), adminToken), 404);
        error(call("/api/v1/admin/users/" + UUID.randomUUID() + "/devices", adminToken), 404);
        error(call("/api/v1/admin/users?role=BOSS", adminToken), 422);
    }

    // ── helpers (test database only) ─────────────────────────────────────────

    private MvcResult call(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        return objectMapper.readTree(body(r)).path("data");
    }

    private void error(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(objectMapper.readTree(body(r)).path("error").asText()).isNotBlank();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.path("id").asText()));
        return ids;
    }

    private static JsonNode find(JsonNode items, String field, String value) {
        for (JsonNode i : items) {
            if (value.equals(i.path(field).asText())) return i;
        }
        return null;
    }

    private void user(UUID id, String name, String role) {
        String[] parts = name.split(" ", 2);
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, password_hash, "
                + "phone_number, role, first_name, last_name) VALUES (?, now(), now(), true, 0, 'hash', ?, ?, ?, ?)",
                id, phone, role, parts[0], parts[1]);
        users.add(id);
    }

    private void session(UUID userId, String schema, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO public.refresh_tokens (id, created_at, updated_at, expires_at, revoked, tenant_id, "
                        + "token_hash, user_id, session_id, device_label, last_used_at) "
                        + "VALUES (?, now(), now(), now() + interval '30 days', false, ?, ?, ?, ?, ?, now())",
                id, schema, UUID.randomUUID().toString(), userId, id, label);
    }

    private void sale(String schema, UUID store, int total, Instant at) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (store_id, employee_id, total_amount, status, occurred_at, "
                + "created_at) VALUES (?, ?, ?, 'COMPLETED', ?, ?)", store, ownerId, total, Timestamp.from(at),
                Timestamp.from(at));
    }

    private UUID client(String schema, UUID store, String name, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone, store_id, created_at, updated_at) "
                + "VALUES (?, ?, '+237690000000', ?, ?, ?)", id, name, store, Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
        return id;
    }

    private UUID product(String schema, String name, boolean archived) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, archived) VALUES (?, ?, ?, ?)",
                id, name, id.toString().substring(0, 8), archived);
        return id;
    }

    private String createExpense(int amount, String category, String label) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", amount, "category", category,
                        "label", label)))).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return objectMapper.readTree(body(r)).path("data").path("id").asText();
    }
}
