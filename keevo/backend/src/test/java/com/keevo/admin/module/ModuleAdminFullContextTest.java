package com.keevo.admin.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 13.8a — the module lifecycle API in the real application context on PostgreSQL, with the shipped catalogue:
 * production security chain and JWTs, production transaction manager and advisory-lock adapter, real sync push.
 * Covers the shipped-catalogue rows of the I/O matrix (crm deactivate → sync push held → reactivate → applied,
 * non-super-admin 403, owner view, dependents / core refusals) and proves that the JDBC row writes join the
 * lifecycle's JPA transaction (a rolled-back call leaves no row).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Module lifecycle API — full context (Story 13.8a)")
class ModuleAdminFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleLifecycle lifecycle;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        // Story 13.9: registration provisions the free modules with migrations (crm) and materialises their
        // ACTIVE/FREE row — these tests start from the free rule without any row
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        adminToken = jwtTokenProvider.generateAccessToken(adminId, "public", "SUPER_ADMIN");
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResultActions adminPost(String action, String moduleId, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/tenants/{tenantId}/modules/{moduleId}/" + action, tenantId,
                moduleId).header("Authorization", "Bearer " + token));
    }

    private static Map<String, Object> createClient(String name) {
        Map<String, Object> op = new HashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", "CREATE_CLIENT");
        op.put("entityId", UUID.randomUUID().toString());
        op.put("payload", Map.of("id", UUID.randomUUID().toString(), "name", name, "phone", "+237690000002"));
        op.put("clientTimestamp", Instant.now().minusSeconds(60).toString());
        return op;
    }

    private ResultActions push(Map<String, Object> op) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("deviceId", "device-lifecycle",
                "operations", List.of(op)));
        return mockMvc.perform(post("/api/v1/sync/push").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> crmRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, disabled_at, activated_at "
                + "FROM public.tenant_modules WHERE tenant_id = ? AND module_id = 'crm'", tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate crm → push CREATE_CLIENT REJECTED MODULE_INACTIVE (held) → activate crm → same op APPLIED; audited")
    void crmLifecycleWithSyncPush() throws Exception {
        adminPost("deactivate", "crm", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        assertThat(crmRow()).containsEntry("status", "DISABLED");
        assertThat(crmRow().get("disabled_at")).isNotNull();

        mockMvc.perform(get("/api/v1/tenants/me/modules").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleId == 'crm')].state", contains("DISABLED")))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'pos')].state", contains("ENABLED")));

        Map<String, Object> client = createClient("Client en attente");
        String opId = (String) client.get("operationId");
        push(client)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].reason").value("MODULE_INACTIVE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE name = ?",
                Integer.class, "Client en attente")).isZero();

        adminPost("activate", "crm", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activated[0].moduleId").value("crm"))
                .andExpect(jsonPath("$.data.activated[0].displayName").value("Clients et fournisseurs"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(crmRow()).containsEntry("status", "ACTIVE").containsEntry("disabled_at", null);

        push(client) // the device retries the held operation
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].operationId").value(opId))
                .andExpect(jsonPath("$.data.results[0].status").value("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE name = ?",
                Integer.class, "Client en attente")).isEqualTo(1);

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, entity_type, entity_label, user_id, "
                + "value_after FROM \"" + schema + "\".audit_log WHERE entity_type = 'Module' ORDER BY occurred_at");
        assertThat(audit).extracting(r -> r.get("action")).containsExactly("MODULE_DEACTIVATED", "MODULE_ACTIVATED");
        assertThat(audit).extracting(r -> r.get("entity_label")).containsOnly("Clients et fournisseurs");
        assertThat(audit).extracting(r -> r.get("user_id")).containsOnly(adminId);
        assertThat(audit).extracting(r -> (String) r.get("value_after"))
                .allSatisfy(v -> assertThat(v).contains("Clients et fournisseurs")
                        .doesNotContain(schema, tenantId.toString(), "\"crm\""));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.audit_log WHERE entity_type = 'Module'",
                Integer.class)).isZero(); // never in the platform schema
    }

    @Test
    @DisplayName("OWNER token on the admin API → 403, nothing written")
    void ownerForbidden() throws Exception {
        adminPost("deactivate", "crm", ownerToken).andExpect(status().isForbidden());
        adminPost("activate", "crm", ownerToken).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/modules").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        assertThat(crmRow()).isNull();
    }

    @Test
    @DisplayName("deactivate inventory while pos is active → 409 listing « Point de vente »; core → 409; unknowns → 404")
    void refusals() throws Exception {
        adminPost("deactivate", "inventory", adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("MODULE_HAS_DEPENDENTS"))
                // 13.10a: the tenant is on its registration trial — the paid feature modules depending on inventory
                // are enabled by the transition rule, so they are dependents too
                .andExpect(jsonPath("$.error").value("Désactivation impossible : ce module est utilisé par "
                        + "« Inventaire assisté », « Multi-boutiques et transferts », « Point de vente »"));
        adminPost("deactivate", "core", adminToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("CORE_MODULE_REQUIRED"));
        adminPost("activate", "ghost", adminToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("MODULE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/admin/tenants/{tenantId}/modules/crm/activate", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("TENANT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ?",
                Integer.class, tenantId)).isZero();
    }

    @Test
    @DisplayName("catalogue and tenant view through HTTP: shipped modules, platform module hidden, states")
    void reads() throws Exception {
        mockMvc.perform(get("/api/v1/admin/modules").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleId == 'pos')].displayName", contains("Point de vente")))
                .andExpect(jsonPath("$.data[?(@.moduleId == 'admin')]").isEmpty());
        mockMvc.perform(get("/api/v1/admin/tenants/{tenantId}/modules", tenantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.moduleId == 'crm')].state", contains("ENABLED")));
    }

    @Test
    @DisplayName("the lifecycle's JDBC writes join its JPA transaction: an enclosing rollback leaves no row, no audit")
    void writesJoinTheTransaction() {
        tx.executeWithoutResult(status -> {
            lifecycle.deactivate(tenantId, "crm", new ModuleLifecycle.Actor(adminId, true));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_modules WHERE tenant_id = ?",
                    Integer.class, tenantId)).isEqualTo(1); // visible on the transaction's own connection
            status.setRollbackOnly();
        });

        assertThat(crmRow()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE entity_type = 'Module'",
                Integer.class)).isZero();
    }
}
