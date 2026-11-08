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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

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
        jdbc.update("DELETE FROM \"" + schemaA + "\".employees WHERE last_name = ?", marker);
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
    @DisplayName("every new read route refuses a non-super-admin (owner) with 403")
    void ownerRefused(String path) throws Exception {
        assertThat(call(path, ownerToken).getResponse().getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /customers", "PATCH /customers/00000000-0000-0000-0000-000000000002",
            "PUT /customers/00000000-0000-0000-0000-000000000002/marketing-consent", "POST /members",
            "PATCH /members/00000000-0000-0000-0000-000000000002",
            "POST /members/00000000-0000-0000-0000-000000000002/deactivate",
            "POST /members/00000000-0000-0000-0000-000000000002/reactivate",
            "DELETE /members/00000000-0000-0000-0000-000000000002"})
    @DisplayName("every write route (A1–A4) refuses a non-super-admin (owner) with 403, nothing written")
    void ownerRefusedOnWrites(String route) throws Exception {
        String[] parts = route.split(" ");
        MvcResult r = mockMvc.perform(request(org.springframework.http.HttpMethod.valueOf(parts[0]),
                        "/api/v1/admin/tenants/" + tenantA + parts[1])
                .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"phone\":\"+237690009999\",\"consent\":true}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
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
        assertThat(detail.path("statusLabel").asText()).isEqualTo("Actif");
    }

    @Test
    @DisplayName("customers: equal sort keys in two accounts at a page boundary — every row once, in order")
    void customersTieAcrossTenants() throws Exception {
        Instant t = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        UUID a = client(schemaA, storeA, "Egal " + marker + " a", t);
        UUID b = client(schemaB, null, "Egal " + marker + " b", t);
        UUID a2 = client(schemaA, storeA, "Egal " + marker + " c", t);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = data(call("/api/v1/admin/customers?q=" + marker + "&limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor), adminToken));
            seen.addAll(ids(page.path("items")));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null && seen.size() < 10);

        assertThat(seen).containsExactlyInAnyOrder(a.toString(), b.toString(), a2.toString()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("detail on an older schema (a detail column missing) → 404, never a 500; the list still works")
    void detailOnOlderSchema() throws Exception {
        UUID c = client(schemaB, null, "Ancienne " + marker, Instant.now());
        jdbc.execute("ALTER TABLE \"" + schemaB + "\".clients DROP COLUMN niu"); // test database only

        try {
            error(call("/api/v1/admin/tenants/" + tenantB + "/customers/" + c, adminToken), 404);
            JsonNode list = data(call("/api/v1/admin/customers?q=" + marker + "&tenantId=" + tenantB, adminToken));
            assertThat(ids(list.path("items"))).containsExactly(c.toString());
        } finally {
            jdbc.execute("ALTER TABLE \"" + schemaB + "\".clients ADD COLUMN IF NOT EXISTS niu VARCHAR(50)");
        }
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

        // Several pages: totalAmount on the first page only, every expense once.
        createExpense(1_000, "telecom", "Forfait " + marker);
        JsonNode p1 = data(call("/api/v1/admin/expenses?tenantId=" + tenantA + "&q=" + marker + "&limit=2",
                adminToken));
        assertThat(p1.path("totalAmount").decimalValue()).isEqualByComparingTo("18500");
        JsonNode p2 = data(call("/api/v1/admin/expenses?tenantId=" + tenantA + "&q=" + marker + "&limit=2&cursor="
                + p1.path("page").path("nextCursor").asText(), adminToken));
        assertThat(p2.path("totalAmount").isNull()).isTrue();
        assertThat(p2.path("page").path("items")).hasSize(1);
        assertThat(ids(p2.path("page").path("items"))).doesNotContainAnyElementsOf(ids(p1.path("page").path("items")));

        // Account B never used the module: no expenses table, empty list, no error.
        JsonNode none = data(call("/api/v1/admin/expenses?tenantId=" + tenantB, adminToken));
        assertThat(none.path("page").path("items")).isEmpty();
        error(call("/api/v1/admin/tenants/" + tenantB + "/expenses/" + e1, adminToken), 404);
        error(call("/api/v1/admin/expenses?category=food", adminToken), 422);
        error(call("/api/v1/admin/expenses?from=2026-11-09&to=2026-11-01", adminToken), 422);
    }

    // ── account writes (decisions A1–A4) ─────────────────────────────────────

    @Test
    @DisplayName("A1: create, update and set the consent of a customer of an account — crm rules, audit by the super-admin")
    void customerWrites() throws Exception {
        MvcResult created = send(post("/api/v1/admin/tenants/" + tenantA + "/customers"), Map.of(
                "name", "Client " + marker, "phone", "+237690001122", "storeId", storeA.toString()));
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        JsonNode customer = objectMapper.readTree(body(created)).path("data").path("customer");
        String id = customer.path("id").asText();
        assertThat(customer.path("phone").asText()).isEqualTo("+237690001122");
        assertThat(customer.path("storeName").asText()).isNotBlank();

        MvcResult updated = send(patch("/api/v1/admin/tenants/" + tenantA + "/customers/" + id),
                Map.of("email", "client@exemple.cm", "clientType", "company"));
        JsonNode u = data(updated);
        assertThat(u.path("email").asText()).isEqualTo("client@exemple.cm");
        assertThat(u.path("clientTypeLabel").asText()).isEqualTo("Entreprise");

        JsonNode consent = data(send(put("/api/v1/admin/tenants/" + tenantA + "/customers/" + id
                + "/marketing-consent"), Map.of("consent", true)));
        assertThat(consent.path("marketingConsent").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT marketing_consent_by FROM \"" + schemaA + "\".clients WHERE id = ?",
                UUID.class, UUID.fromString(id))).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schemaA + "\".audit_log WHERE entity_id = ? "
                + "AND user_id = ?", Integer.class, UUID.fromString(id), adminId)).isGreaterThanOrEqualTo(1);

        MvcResult missing = send(post("/api/v1/admin/tenants/" + tenantA + "/customers"), Map.of("phone", "+237690001133"));
        assertThat(missing.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(body(missing)).path("error").asText()).isEqualTo("Le nom du client est obligatoire");
        assertThat(send(put("/api/v1/admin/tenants/" + tenantA + "/customers/" + UUID.randomUUID()
                + "/marketing-consent"), Map.of("consent", false)).getResponse().getStatus()).isEqualTo(404);
        assertThat(send(post("/api/v1/admin/tenants/" + UUID.randomUUID() + "/customers"),
                Map.of("name", "X", "phone", "+237690001144")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("A2–A4: add, update, deactivate (sessions revoked), reactivate and remove a member; never the owner")
    void memberWrites() throws Exception {
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        MvcResult created = send(post("/api/v1/admin/tenants/" + tenantA + "/members"), Map.of(
                "phoneNumber", phone, "firstName", "Marie", "lastName", marker, "storeId", storeA.toString(),
                "role", "MANAGER"));
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        JsonNode member = objectMapper.readTree(body(created)).path("data");
        UUID userId = UUID.fromString(member.path("userId").asText());
        assertThat(member.path("employeeId").asText()).isNotBlank();
        users.add(userId);
        assertThat(member.path("temporaryPassword").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                String.class, userId, tenantA)).isEqualTo("MANAGER");

        JsonNode updated = data(send(patch("/api/v1/admin/tenants/" + tenantA + "/members/" + userId),
                Map.of("firstName", "Mariette", "role", "VIEWER")));
        assertThat(updated.path("firstName").asText()).isEqualTo("Mariette");
        assertThat(jdbc.queryForObject("SELECT role FROM public.user_tenant_memberships WHERE user_id = ? AND tenant_id = ?",
                String.class, userId, tenantA)).isEqualTo("VIEWER");

        session(userId, schemaA, "Tecno Spark");
        JsonNode off = data(send(post("/api/v1/admin/tenants/" + tenantA + "/members/" + userId + "/deactivate"), null));
        assertThat(off.path("active").asBoolean()).isFalse();
        assertThat(off.path("statusLabel").asText()).isEqualTo("Désactivé");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.refresh_tokens WHERE user_id = ? AND revoked = false",
                Integer.class, userId)).isZero();
        assertThat(data(send(post("/api/v1/admin/tenants/" + tenantA + "/members/" + userId + "/reactivate"), null))
                .path("active").asBoolean()).isTrue();

        MvcResult removed = send(delete("/api/v1/admin/tenants/" + tenantA + "/members/" + userId), null);
        assertThat(removed.getResponse().getStatus()).as(body(removed)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.user_tenant_memberships WHERE user_id = ? "
                + "AND tenant_id = ?", Integer.class, userId, tenantA)).isZero();

        // The owner is never removed (an owner with an employee row, e.g. promoted).
        UUID ownerEmployee = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schemaA + "\".employees (id, user_id, store_id, first_name, last_name, status) "
                + "VALUES (?, ?, ?, 'Awa', ?, 'ACTIVE')", ownerEmployee, ownerId, storeA, marker);
        MvcResult owner = send(delete("/api/v1/admin/tenants/" + tenantA + "/members/" + ownerId), null);
        assertThat(owner.getResponse().getStatus()).as(body(owner)).isEqualTo(403);
        assertThat(objectMapper.readTree(body(owner)).path("error").asText())
                .isEqualTo("Le propriétaire du compte ne peut pas être supprimé");
        assertThat(send(delete("/api/v1/admin/tenants/" + tenantA + "/members/" + UUID.randomUUID()), null)
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult badRole = send(post("/api/v1/admin/tenants/" + tenantA + "/members"), Map.of(
                "phoneNumber", phone, "firstName", "X", "storeId", storeA.toString(), "role", "OWNER"));
        assertThat(badRole.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(body(badRole)).path("error").asText())
                .isEqualTo("Rôle inconnu : choisissez MANAGER, EMPLOYEE ou VIEWER");
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

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                           Object body) throws Exception {
        request.header("Authorization", "Bearer " + adminToken);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
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
