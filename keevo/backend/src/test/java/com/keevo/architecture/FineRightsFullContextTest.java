package com.keevo.architecture;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.5b — the fine rights of the MANAGER and the VIEWER, with the production security filter, JWTs, module
 * gates and the shipped manifests (one tenant, stores A and B): store-wide sales history (MANAGER, VIEWER) vs own sales
 * (EMPLOYEE), cancelling a seller's inventory session (MANAGER of the store), merging products (OWNER, MANAGER), a
 * seller's device sessions (team rule) and reactivation of a member the owner deactivated (owner only).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Fine rights of MANAGER and VIEWER (Story 19.5b)")
class FineRightsFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;

    record Member(UUID user, UUID employee, String token) {
    }

    private String schema;
    private UUID storeA;
    private UUID storeB;
    private String owner;
    private Member managerA;
    private Member seller1;
    private Member seller2;
    private Member viewerA;
    private Member sellerB;

    @BeforeEach
    void setUp() {
        schema = tx.execute(st -> tenantFactory.create()).getSchemaName();
        UUID tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'counting', "
                + "'ACTIVE', 'PREMIUM') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = 'ACTIVE'", tenantId);
        moduleRegistry.evict(schema);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, now() + interval '1 minute', now(), true, 'Boutique Bonapriso', 'STORE')", storeB);
        owner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        managerA = member("MANAGER", storeA);
        seller1 = member("EMPLOYEE", storeA);
        seller2 = member("EMPLOYEE", storeA);
        viewerA = member("VIEWER", storeA);
        sellerB = member("EMPLOYEE", storeB);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                + "schema_name = ?)", schema);
        moduleRegistry.evict(schema);
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

    private void sale(UUID seller, UUID store) {
        Timestamp now = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, 5000, 0)",
                UUID.randomUUID(), now, seller, now, store);
    }

    private long history(Member m, UUID store) throws Exception {
        return expect(call(m.token(), HttpMethod.GET, "/api/v1/sales/history?storeId=" + store, null), 200)
                .path("data").path("totalElements").asLong();
    }

    private UUID session(UUID store, UUID startedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".inventory_sessions (id, store_id, scope, started_by) "
                + "VALUES (?, ?, 'FULL', ?)", id, store, startedBy);
        return id;
    }

    private String sessionStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM \"" + schema + "\".inventory_sessions WHERE id = ?",
                String.class, id);
    }

    private UUID product(String name, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, status, price) "
                + "VALUES (?, ?, ?, 'ACTIVE', 1000)", id, name, sku);
        return id;
    }

    private boolean deactivatedByManager(Member m) {
        return jdbc.queryForObject("SELECT deactivated_by_manager FROM public.user_tenant_memberships "
                + "WHERE user_id = ?", Boolean.class, m.user());
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sales history: MANAGER and VIEWER see every sale of their store, an EMPLOYEE his own, another store "
            + "is refused in French; the OWNER unchanged")
    void salesHistory() throws Exception {
        sale(seller1.user(), storeA);
        sale(seller2.user(), storeA);
        sale(sellerB.user(), storeB);

        assertThat(history(managerA, storeA)).isEqualTo(2);
        assertThat(history(viewerA, storeA)).isEqualTo(2);
        assertThat(history(seller1, storeA)).isEqualTo(1);
        assertThat(expect(call(owner, HttpMethod.GET, "/api/v1/sales/history?storeId=" + storeA, null), 200)
                .path("data").path("totalElements").asLong()).isEqualTo(2);
        for (Member m : List.of(managerA, viewerA, seller1)) {
            MvcResult r = call(m.token(), HttpMethod.GET, "/api/v1/sales/history?storeId=" + storeB, null);
            expect(r, 403);
            assertThat(body(r)).contains("Vous ne pouvez consulter que les ventes de votre boutique.");
        }
    }

    @Test
    @DisplayName("inventory session: the MANAGER cancels a seller's session of his store, not another store's; a "
            + "seller never cancels a colleague's")
    void inventorySessionCancel() throws Exception {
        UUID ofSeller1 = session(storeA, seller1.user());
        expect(call(seller2.token(), HttpMethod.POST, "/api/v1/inventory/sessions/" + ofSeller1 + "/cancel", null),
                403);
        assertThat(sessionStatus(ofSeller1)).isEqualTo("IN_PROGRESS");
        expect(call(managerA.token(), HttpMethod.POST, "/api/v1/inventory/sessions/" + ofSeller1 + "/cancel", null),
                200);
        assertThat(sessionStatus(ofSeller1)).isEqualTo("CANCELLED");

        UUID inB = session(storeB, sellerB.user());
        MvcResult r = call(managerA.token(), HttpMethod.POST, "/api/v1/inventory/sessions/" + inB + "/cancel", null);
        expect(r, 403);
        assertThat(body(r)).contains("Vous ne pouvez gérer que les inventaires de votre boutique.");
        assertThat(sessionStatus(inB)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("product merge: an EMPLOYEE is refused (nothing merged), a MANAGER merges")
    void productMerge() throws Exception {
        UUID source = product("Savon Lux 100 g", "KEV-LUX100");
        UUID target = product("Savon Lux", "KEV-LUX001");

        expect(call(seller1.token(), HttpMethod.POST, "/api/v1/products/" + source + "/merge",
                Map.of("targetId", target.toString())), 403);
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM \"" + schema + "\".products WHERE id = ?",
                UUID.class, source)).isNull();

        JsonNode merged = expect(call(managerA.token(), HttpMethod.POST, "/api/v1/products/" + source + "/merge",
                Map.of("targetId", target.toString())), 200);
        assertThat(merged.path("data").path("id").asText()).isEqualTo(target.toString());
    }

    @Test
    @DisplayName("device sessions of a member: the MANAGER for the sellers of his store; never another store's "
            + "seller, a colleague manager, nor for a seller")
    void deviceSessions() throws Exception {
        expect(call(managerA.token(), HttpMethod.GET, "/api/v1/employees/" + seller1.employee() + "/sessions", null),
                200);
        expect(call(managerA.token(), HttpMethod.DELETE, "/api/v1/employees/" + seller1.employee() + "/sessions",
                null), 200);
        expect(call(managerA.token(), HttpMethod.GET, "/api/v1/employees/" + sellerB.employee() + "/sessions", null),
                403);
        expect(call(seller2.token(), HttpMethod.GET, "/api/v1/employees/" + seller1.employee() + "/sessions", null),
                403);
        expect(call(owner, HttpMethod.GET, "/api/v1/employees/" + managerA.employee() + "/sessions", null), 200);

        // the closing endpoints follow the same team rule
        Member managerA2 = member("MANAGER", storeA);
        UUID anySession = UUID.randomUUID();
        for (String path : List.of("/sessions", "/sessions/" + anySession)) {
            expect(call(managerA.token(), HttpMethod.DELETE, "/api/v1/employees/" + sellerB.employee() + path, null),
                    403);
            expect(call(seller2.token(), HttpMethod.DELETE, "/api/v1/employees/" + seller1.employee() + path, null),
                    403);
            expect(call(managerA.token(), HttpMethod.DELETE, "/api/v1/employees/" + managerA2.employee() + path,
                    null), 403);
        }
    }

    @Test
    @DisplayName("reactivation: a member the OWNER deactivated stays owner-only (French 403); one the MANAGER "
            + "deactivated is reactivated by him; the flag is recorded then cleared")
    void reactivation() throws Exception {
        expect(call(owner, HttpMethod.PATCH, "/api/v1/employees/" + seller1.employee() + "/deactivate", null), 200);
        assertThat(deactivatedByManager(seller1)).isFalse();
        MvcResult refused = call(managerA.token(), HttpMethod.PATCH,
                "/api/v1/employees/" + seller1.employee() + "/reactivate", null);
        expect(refused, 403);
        assertThat(body(refused)).contains("Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver.");
        // deactivating him again does not turn the owner's deactivation into the manager's
        expect(call(managerA.token(), HttpMethod.PATCH, "/api/v1/employees/" + seller1.employee() + "/deactivate",
                null), 200);
        assertThat(deactivatedByManager(seller1)).isFalse();
        expect(call(managerA.token(), HttpMethod.PATCH, "/api/v1/employees/" + seller1.employee() + "/reactivate",
                null), 403);

        expect(call(managerA.token(), HttpMethod.PATCH, "/api/v1/employees/" + seller2.employee() + "/deactivate",
                null), 200);
        assertThat(deactivatedByManager(seller2)).isTrue();
        expect(call(managerA.token(), HttpMethod.PATCH, "/api/v1/employees/" + seller2.employee() + "/reactivate",
                null), 200);
        assertThat(deactivatedByManager(seller2)).isFalse();

        expect(call(owner, HttpMethod.PATCH, "/api/v1/employees/" + seller1.employee() + "/reactivate", null), 200);
        List<Boolean> active = new ArrayList<>(jdbc.queryForList("SELECT is_active FROM public.user_tenant_memberships "
                + "WHERE user_id IN (?, ?)", Boolean.class, seller1.user(), seller2.user()));
        assertThat(active).containsOnly(true);
    }
}
