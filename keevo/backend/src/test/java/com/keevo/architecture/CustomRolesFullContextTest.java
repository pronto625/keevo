package com.keevo.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.14 — roles defined by the owner, with the production security filter, JWTs, module gates and the shipped
 * manifests (one company, stores A and B): the interim acceptance (« Caissier » sells and reads expenses, nothing
 * else), a role without « Créer des ventes » (REST and sync), the editor's validation, the store restriction, the sales
 * pull without « Voir les ventes de toute la boutique », and team management over sync by a delegate.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Owner-defined roles and permissions (Story 19.14)")
class CustomRolesFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AccessStateService accessStates;

    record Member(UUID user, UUID employee, String token) {
    }

    private String schema;
    private UUID storeA;
    private UUID storeB;
    private UUID product;
    private String owner;
    private Member managerA;
    private Member seller1;
    private Member seller2;

    @BeforeEach
    void setUp() {
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        UUID tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        for (String module : List.of("expenses", "invoicing", "multi-store", "reporting", "finance")) {
            jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, "
                    + "'ACTIVE', 'PREMIUM') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = 'ACTIVE'",
                    tenantId, module);
        }
        jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ?", tenantId);
        moduleRegistry.evict(schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status, price, buy_price, transport_cost) "
                + "VALUES (?, 'Riz parfumé 25 kg', 'KEV-RIZ025', 'ACTIVE', 1000, 600, 50)", product);
        owner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        managerA = member("MANAGER", storeA);
        seller1 = member("EMPLOYEE", storeA);
        seller2 = member("EMPLOYEE", storeA);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE public.user_tenant_memberships SET custom_role_id = NULL WHERE tenant_id = "
                + "(SELECT id FROM public.tenants WHERE schema_name = ?)", schema);
        jdbc.update("DELETE FROM public.tenant_roles WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                + "schema_name = ?)", schema);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                + "schema_name = ?)", schema);
        moduleRegistry.evict(schema);
        accessStates.invalidateTenant(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private Member member(String role, UUID store) {
        UUID user = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Paul', 'Ekane', false)", employee, user, store);
        return new Member(user, employee,
                TestAccess.token(jdbc, jwtTokenProvider, user, schema, role, "ACTIVE", store, false));
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(request).andReturn();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        return r;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode expect(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r));
    }

    private int status(String token, HttpMethod method, String path, Object body) throws Exception {
        return call(token, method, path, body).getResponse().getStatus();
    }

    private UUID createRole(String name, String baseRole, List<String> permissions, List<UUID> stores)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("baseRole", baseRole);
        body.put("permissions", permissions);
        body.put("storeIds", stores);
        return UUID.fromString(expect(call(owner, HttpMethod.POST, "/api/v1/roles", body), 201)
                .path("data").path("id").asText());
    }

    private void assign(Member m, UUID role) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("roleId", role);
        expect(call(owner, HttpMethod.PUT, "/api/v1/employees/" + m.employee() + "/custom-role", body), 200);
    }

    private List<String> myPermissions(Member m) throws Exception {
        List<String> out = new ArrayList<>();
        expect(call(m.token(), HttpMethod.GET, "/api/v1/roles/me", null), 200).path("data").path("permissions")
                .forEach(n -> out.add(n.asText()));
        return out;
    }

    private Map<String, Object> saleBody() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", product.toString());
        item.put("productName", "Riz parfumé 25 kg");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", UUID.randomUUID().toString());
        body.put("paymentMode", "CASH");
        body.put("storeId", storeA.toString());
        body.put("items", List.of(item));
        return body;
    }

    private JsonNode push(Member m, String type, Map<String, Object> payload) throws Exception {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        if (type.endsWith("_EXPENSE") && payload.get("id") != null) {
            op.put("entityId", payload.get("id")); // the expense id (expense sync contract)
        }
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().toString());
        return expect(call(m.token(), HttpMethod.POST, "/api/v1/sync/push",
                Map.of("deviceId", UUID.randomUUID().toString(), "operations", List.of(op))), 200)
                .path("data").path("results").get(0);
    }

    private void sale(UUID seller, UUID store) {
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, 5000, 0)",
                UUID.randomUUID(), at, seller, at, store);
    }

    private List<JsonNode> pulledSales(Member m) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + m.token());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode data = expect(mockMvc.perform(request).andReturn(), 200).path("data");
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            data.path("entities").path("sales").forEach(rows::add);
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("interim AC: « Caissier » (Créer des ventes + Voir les dépenses) sells and reads expenses, nothing "
            + "else; ticking « Voir les prix d'achat » shows the purchase price at once")
    void cashierRole() throws Exception {
        JsonNode catalogue = expect(call(owner, HttpMethod.GET, "/api/v1/roles/permissions", null), 200).path("data");
        assertThat(catalogue.toString()).contains("Créer des ventes").contains("Voir les dépenses")
                .contains("Voir la situation financière"); // reprise 11/11/2026: grantable

        UUID cashier = createRole("Caissier", "EMPLOYEE", List.of("SALE_CREATE", "EXPENSE_VIEW"), null);
        assign(seller1, cashier);

        JsonNode me = expect(call(seller1.token(), HttpMethod.GET, "/api/v1/roles/me", null), 200).path("data");
        assertThat(me.path("customRole").path("name").asText()).isEqualTo("Caissier");
        assertThat(myPermissions(seller1)).containsExactly("EXPENSE_VIEW", "SALE_CREATE");
        assertThat(myPermissions(seller2)).contains("SALE_CREATE", "INVOICE_VIEW").doesNotContain("EXPENSE_VIEW");

        assertThat(status(seller1.token(), HttpMethod.GET, "/api/v1/expenses", null)).isEqualTo(200);
        assertThat(status(seller2.token(), HttpMethod.GET, "/api/v1/expenses", null)).isEqualTo(403);
        assertThat(status(seller1.token(), HttpMethod.GET, "/api/v1/invoices", null)).isEqualTo(403);
        assertThat(status(seller2.token(), HttpMethod.GET, "/api/v1/invoices", null)).isEqualTo(200);
        assertThat(status(seller1.token(), HttpMethod.POST, "/api/v1/sales", saleBody())).isNotEqualTo(403);
        assertThat(status(seller1.token(), HttpMethod.POST, "/api/v1/products/" + product + "/merge",
                Map.of("targetId", UUID.randomUUID().toString()))).isEqualTo(403);

        JsonNode hidden = expect(call(seller1.token(), HttpMethod.GET, "/api/v1/products/" + product, null), 200);
        assertThat(hidden.path("data").path("buyPrice").isNull()).isTrue();

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "Caissier");
        update.put("permissions", List.of("SALE_CREATE", "EXPENSE_VIEW", "PURCHASE_PRICE_VIEW"));
        expect(call(owner, HttpMethod.PUT, "/api/v1/roles/" + cashier, update), 200);
        JsonNode shown = expect(call(seller1.token(), HttpMethod.GET, "/api/v1/products/" + product, null), 200);
        assertThat(shown.path("data").path("buyPrice").asInt()).isEqualTo(600);

        JsonNode team = expect(call(owner, HttpMethod.GET, "/api/v1/employees", null), 200).path("data");
        boolean labelled = false;
        for (JsonNode e : team) {
            if (seller1.employee().toString().equals(e.path("id").asText())) {
                labelled = "Caissier".equals(e.path("customRoleName").asText());
            }
        }
        assertThat(labelled).as(team.toString()).isTrue();

        JsonNode roles = expect(call(owner, HttpMethod.GET, "/api/v1/roles", null), 200).path("data");
        assertThat(roles.path("custom").get(0).path("memberCount").asInt()).isEqualTo(1);
        assertThat(roles.path("builtIn").toString()).contains("Gérant").contains("Lecteur");

        // choosing a built-in role takes the custom role back
        expect(call(owner, HttpMethod.PATCH, "/api/v1/employees/" + seller1.employee() + "/role",
                Map.of("role", "EMPLOYEE")), 200);
        accessStates.invalidateTenant(schema);
        assertThat(myPermissions(seller1)).doesNotContain("EXPENSE_VIEW").contains("INVOICE_VIEW");
    }

    @Test
    @DisplayName("« Magasinier » without « Créer des ventes »: REST sale 403, sync CREATE_SALE rejected")
    void roleWithoutSelling() throws Exception {
        UUID storekeeper = createRole("Magasinier", "EMPLOYEE", List.of("PURCHASE_PRICE_VIEW"), null);
        assign(seller1, storekeeper);

        assertThat(status(seller1.token(), HttpMethod.POST, "/api/v1/sales", saleBody())).isEqualTo(403);
        assertThat(status(seller2.token(), HttpMethod.POST, "/api/v1/sales", saleBody())).isNotEqualTo(403);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", product.toString());
        item.put("productName", "Riz parfumé 25 kg");
        item.put("unitPrice", 1000);
        item.put("quantity", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("saleId", UUID.randomUUID().toString());
        payload.put("storeId", storeA.toString());
        payload.put("paymentMode", "CASH");
        payload.put("items", List.of(item));
        JsonNode result = push(seller1, "CREATE_SALE", payload);
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("REJECTED");
        assertThat(result.toString()).contains("FORBIDDEN");
    }

    @Test
    @DisplayName("editor: owner only, French validation, unique names, viewer roles read-only, finance grantable, "
            + "an assigned role cannot be deleted")
    void editorValidation() throws Exception {
        assertThat(status(managerA.token(), HttpMethod.GET, "/api/v1/roles", null)).isEqualTo(403);
        assertThat(status(seller1.token(), HttpMethod.POST, "/api/v1/roles",
                Map.of("name", "X", "baseRole", "EMPLOYEE", "permissions", List.of()))).isEqualTo(403);

        MvcResult viewerWrite = call(owner, HttpMethod.POST, "/api/v1/roles",
                Map.of("name", "Contrôleur", "baseRole", "VIEWER", "permissions", List.of("EXPENSE_CREATE")));
        assertThat(viewerWrite.getResponse().getStatus()).isIn(400, 422);
        assertThat(body(viewerWrite)).contains("ne peut recevoir que des droits de consultation");

        // reprise 11/11/2026 — « Voir la situation financière » is the owner's choice (DD4 reversed)
        createRole("Comptable", "MANAGER", List.of("FINANCE_VIEW"), null);

        UUID role = createRole("Vendeur plus", "EMPLOYEE", List.of("SALE_CREATE"), null);
        expect(call(owner, HttpMethod.POST, "/api/v1/roles",
                Map.of("name", "VENDEUR PLUS", "baseRole", "EMPLOYEE", "permissions", List.of())), 409);

        assign(seller1, role);
        expect(call(owner, HttpMethod.DELETE, "/api/v1/roles/" + role, null), 409);
        expect(call(owner, HttpMethod.PUT, "/api/v1/employees/" + seller1.employee() + "/custom-role",
                new HashMap<>(Map.of())), 200); // roleId absent → taken back
        expect(call(owner, HttpMethod.DELETE, "/api/v1/roles/" + role, null), 200);

        MvcResult ownerTarget = call(owner, HttpMethod.PUT, "/api/v1/employees/" + UUID.randomUUID() + "/custom-role",
                Map.of("roleId", UUID.randomUUID().toString()));
        assertThat(ownerTarget.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("store restriction: a role valid in store B cannot be given to a seller of A; a member moved out of "
            + "the role's stores holds no permission (fail closed)")
    void storeRestriction() throws Exception {
        UUID onlyB = createRole("Caissier Bonapriso", "EMPLOYEE", List.of("SALE_CREATE"), List.of(storeB));
        MvcResult refused = call(owner, HttpMethod.PUT, "/api/v1/employees/" + seller1.employee() + "/custom-role",
                Map.of("roleId", onlyB.toString()));
        assertThat(refused.getResponse().getStatus()).isIn(400, 422);
        assertThat(body(refused)).contains("pas valable dans la boutique");

        UUID onlyA = createRole("Caissier Akwa", "EMPLOYEE", List.of("SALE_CREATE"), List.of(storeA));
        assign(seller1, onlyA);
        assertThat(myPermissions(seller1)).containsExactly("SALE_CREATE");
        jdbc.update("UPDATE \"" + schema + "\".employees SET store_id = ? WHERE id = ?", storeB, seller1.employee());
        accessStates.invalidateTenant(schema);
        String moved = TestAccess.token(jdbc, jwtTokenProvider, seller1.user(), schema, "EMPLOYEE", "ACTIVE", storeB,
                false);
        assertThat(myPermissions(new Member(seller1.user(), seller1.employee(), moved))).isEmpty();
    }

    @Test
    @DisplayName("sales pull: an EMPLOYEE gets his own sales, a MANAGER every sale of his store (routed from 19.5b)")
    void salesPull() throws Exception {
        sale(seller1.user(), storeA);
        sale(seller2.user(), storeA);
        sale(seller2.user(), storeB);

        List<JsonNode> mine = pulledSales(seller1);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("employeeId").asText()).isEqualTo(seller1.user().toString());
        assertThat(pulledSales(managerA)).hasSize(2);

        UUID wide = createRole("Chef de caisse", "EMPLOYEE", List.of("SALE_CREATE", "STORE_SALES_VIEW"), null);
        assign(seller1, wide);
        assertThat(pulledSales(seller1)).hasSize(2);
    }

    @Test
    @DisplayName("team over sync: a MANAGER deactivates and reactivates a seller of his store (recorded as a "
            + "delegate's), never moves him; a seller cannot (routed from 19.5b)")
    void teamOverSync() throws Exception {
        JsonNode refused = push(seller1, "DEACTIVATE_EMPLOYEE", Map.of("employeeId", seller2.employee().toString()));
        assertThat(refused.path("status").asText()).isEqualTo("REJECTED");

        JsonNode moved = push(managerA, "REASSIGN_EMPLOYEE", Map.of("employeeId", seller2.employee().toString(),
                "newStoreId", storeB.toString()));
        assertThat(moved.path("status").asText()).isEqualTo("REJECTED");

        JsonNode deactivated = push(managerA, "DEACTIVATE_EMPLOYEE",
                Map.of("employeeId", seller2.employee().toString()));
        assertThat(deactivated.path("status").asText()).as(deactivated.toString()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT deactivated_by_manager FROM public.user_tenant_memberships "
                + "WHERE user_id = ?", Boolean.class, seller2.user())).isTrue();

        JsonNode reactivated = push(managerA, "REACTIVATE_EMPLOYEE",
                Map.of("employeeId", seller2.employee().toString()));
        assertThat(reactivated.path("status").asText()).as(reactivated.toString()).isEqualTo("APPLIED");

        // a delegate never manages another delegate (a member whose custom role grants TEAM_MANAGE)
        UUID chief = createRole("Chef d'équipe", "EMPLOYEE", List.of("SALE_CREATE", "TEAM_MANAGE"), null);
        assign(seller2, chief);
        JsonNode peer = push(managerA, "DEACTIVATE_EMPLOYEE", Map.of("employeeId", seller2.employee().toString()));
        assertThat(peer.path("status").asText()).as(peer.toString()).isEqualTo("REJECTED");
        assertThat(status(managerA.token(), HttpMethod.PATCH,
                "/api/v1/employees/" + seller2.employee() + "/deactivate", null)).isEqualTo(403);
    }

    @Test
    @DisplayName("assignment: an owner membership is refused in French; a PUT without the rights list is refused")
    void assignmentAndUpdateGuards() throws Exception {
        Member coOwner = member("OWNER", storeA);
        UUID role = createRole("Caissier", "EMPLOYEE", List.of("SALE_CREATE"), null);
        MvcResult refused = call(owner, HttpMethod.PUT, "/api/v1/employees/" + coOwner.employee() + "/custom-role",
                Map.of("roleId", role.toString()));
        assertThat(refused.getResponse().getStatus()).isIn(400, 422);
        assertThat(body(refused)).contains("Le propriétaire a déjà tous les droits.");

        MvcResult noList = call(owner, HttpMethod.PUT, "/api/v1/roles/" + role, Map.of("name", "Caissier 2"));
        assertThat(noList.getResponse().getStatus()).isIn(400, 422);
        expect(call(owner, HttpMethod.PUT, "/api/v1/roles/" + role, Map.of("name", "Caissier 2",
                "permissions", List.of("SALE_CREATE", "EXPENSE_VIEW"))), 200);
    }

    // ── reprise 11/11/2026 (user decisions) ──────────────────────────────────────────────────────────────

    private void expense(UUID store, int amount) throws Exception {
        expect(call(owner, HttpMethod.POST, "/api/v1/expenses", Map.of("amount", amount, "category", "rent",
                "storeId", store.toString())), 201);
    }

    private List<JsonNode> pulledEntities(Member m, String entity) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            var request = MockMvcRequestBuilders.get("/api/v1/sync/pull").header("Authorization", "Bearer " + m.token());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode data = expect(mockMvc.perform(request).andReturn(), 200).path("data");
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            data.path("entities").path(entity).forEach(rows::add);
            cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asText() : null;
        } while (cursor != null);
        return rows;
    }

    @Test
    @DisplayName("reprise 11/11/2026 — expenses are the owner's by default: a built-in MANAGER gets a French 403 on "
            + "REST, a REJECTED push, no expense in his pull; a role ticking « Voir les dépenses » restores them")
    void expensesOwnerOnlyByDefault() throws Exception {
        expense(storeA, 4000);
        assertThat(myPermissions(managerA)).doesNotContain("EXPENSE_VIEW", "EXPENSE_CREATE", "EXPENSE_DELETE");
        MvcResult refused = call(managerA.token(), HttpMethod.GET, "/api/v1/expenses", null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(refused)).contains("Vous n'avez pas les droits nécessaires");
        assertThat(status(managerA.token(), HttpMethod.POST, "/api/v1/expenses",
                Map.of("amount", 1000, "category", "rent"))).isEqualTo(403);
        JsonNode pushed = push(managerA, "CREATE_EXPENSE", Map.of("id", UUID.randomUUID().toString(),
                "amount", 1000, "category", "rent"));
        assertThat(pushed.path("status").asText()).as(pushed.toString()).isEqualTo("REJECTED");
        assertThat(pushed.toString()).contains("réservées au propriétaire");
        assertThat(pulledEntities(managerA, "expenses")).isEmpty();

        UUID storeManager = createRole("Gérant comptable", "MANAGER",
                List.of("EXPENSE_VIEW", "EXPENSE_CREATE", "STORE_SALES_VIEW"), null);
        assign(managerA, storeManager);
        assertThat(status(managerA.token(), HttpMethod.GET, "/api/v1/expenses", null)).isEqualTo(200);
        assertThat(pulledEntities(managerA, "expenses")).hasSize(1);
    }

    @Test
    @DisplayName("reprise 11/11/2026 — « Voir la situation financière » granted to a manager: summary of his store "
            + "only (totals, no per-store rows, another store's filter ignored); the owner sees every store")
    void financeForAMember() throws Exception {
        sale(seller1.user(), storeA);
        sale(seller2.user(), storeB);
        sale(seller2.user(), storeB);
        expense(storeA, 1000);
        expense(storeB, 3000);

        assertThat(status(managerA.token(), HttpMethod.GET, "/api/v1/finance/summary", null)).isEqualTo(403);
        UUID accountant = createRole("Gérant comptable", "MANAGER", List.of("FINANCE_VIEW"), null);
        assign(managerA, accountant);
        assertThat(myPermissions(managerA)).containsExactly("FINANCE_VIEW");

        JsonNode mine = expect(call(managerA.token(), HttpMethod.GET, "/api/v1/finance/summary", null), 200)
                .path("data");
        assertThat(mine.path("revenue").path("ttc").decimalValue()).isEqualByComparingTo("5000");
        assertThat(mine.path("expenses").path("total").decimalValue()).isEqualByComparingTo("1000");
        assertThat(mine.path("result").path("net").decimalValue()).isEqualByComparingTo("4000");
        assertThat(mine.path("byStore").isMissingNode() || mine.path("byStore").isNull()
                || mine.path("byStore").isEmpty()).as(mine.toString()).isTrue();
        assertThat(mine.toString()).doesNotContain("Bonapriso").doesNotContain(storeB.toString());

        JsonNode filtered = expect(call(managerA.token(), HttpMethod.GET, "/api/v1/finance/summary?storeId=" + storeB,
                null), 200).path("data");
        assertThat(filtered.path("revenue").path("ttc").decimalValue()).isEqualByComparingTo("5000");
        assertThat(filtered.path("expenses").path("total").decimalValue()).isEqualByComparingTo("1000");

        JsonNode all = expect(call(owner, HttpMethod.GET, "/api/v1/finance/summary", null), 200).path("data");
        assertThat(all.path("revenue").path("ttc").decimalValue()).isEqualByComparingTo("15000");
        assertThat(all.path("expenses").path("total").decimalValue()).isEqualByComparingTo("4000");
        assertThat(all.path("byStore")).hasSize(2);

        // a role limited to store B cannot be given to the manager of A (no « all stores » reach for a member)
        UUID onlyB = createRole("Comptable Bonapriso", "MANAGER", List.of("FINANCE_VIEW"), List.of(storeB));
        MvcResult refused = call(owner, HttpMethod.PUT, "/api/v1/employees/" + managerA.employee() + "/custom-role",
                Map.of("roleId", onlyB.toString()));
        assertThat(refused.getResponse().getStatus()).isIn(400, 422);
        assertThat(body(refused)).contains("pas valable dans la boutique");
    }
}
