package com.keevo.admin.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 16.8 — plan profiles end to end in the real application context on PostgreSQL: the V33 seed, the super-admin
 * API (403 / 422 / re-sync report), a limit change applied to the next creation through the real store endpoint
 * (no restart), a lowered limit keeping the existing records, the missing-row fallback, {@code GET
 * /subscription/me} reading the profile, the plan-derived module rows following the profile composition (ADMIN rows
 * untouched). Every test restores the seeded profiles (and so the rows of the
 * shared database's tenants) through the same API.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Plan profiles and configurable limits — full context (Story 16.8)")
class PlanProfilePostgresIntegrationTest extends FullContextIntegrationTest {

    private static final List<String> PAID = List.of("advanced-reports", "alerts", "counting", "exports",
            "invoicing", "multi-store", "profitability", "report-config");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanProfileAdmin profileAdmin;
    @Autowired PlanProfiles planProfiles;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired ModuleRegistry registry;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String tenantCode;
    private String adminToken;
    private String ownerToken;
    private final List<UUID> extraTenants = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        tenantCode = jdbc.queryForObject("SELECT code FROM public.tenants WHERE id = ?", String.class, tenantId);
        adminToken = jwtTokenProvider.generateAccessToken(adminId, "public", "SUPER_ADMIN");
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
    }

    @AfterEach
    void restoreSeed() {
        profileAdmin.update(PlanType.FREE, 1, 500, 2, Set.of(), adminId);
        profileAdmin.update(PlanType.PREMIUM_TRIAL, null, null, null, Set.copyOf(PAID), adminId);
        profileAdmin.update(PlanType.PREMIUM, null, null, null, Set.copyOf(PAID), adminId);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        for (UUID extra : extraTenants) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", extra);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void plan(String schemaName, UUID id, String planType, String expiresSql) {
        jdbc.update("UPDATE \"" + schemaName + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', expires_at = "
                + expiresSql, planType);
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", planType, id);
        rowSync.sync(id, schemaName);
    }

    private MvcResult putProfile(String planType, Map<String, Object> body, String token) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/plan-profiles/{planType}", planType)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private static Map<String, Object> profile(Integer stores, Integer products, Integer employees,
                                               List<String> modules) {
        Map<String, Object> body = new HashMap<>();
        body.put("maxStores", stores);
        body.put("maxProducts", products);
        body.put("maxEmployees", employees);
        body.put("modules", modules);
        return body;
    }

    private MvcResult createStore(String name) throws Exception {
        return mockMvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name)))).andReturn();
    }

    private int stores() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".stores WHERE is_active", Integer.class);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String row(UUID id, String moduleId) {
        List<String> rows = jdbc.queryForList("SELECT status || '/' || tier || '/' || origin || '/' "
                + "|| coalesce(expires_at::text, '-') FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?",
                String.class, id, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("seed: FREE 1/500/2 without paid module; premium profiles unlimited with the 8 paid modules")
    void seed() throws Exception {
        assertThat(jdbc.queryForList("SELECT plan_type || ':' || coalesce(max_stores::text, '∞') || '/' "
                + "|| coalesce(max_products::text, '∞') || '/' || coalesce(max_employees::text, '∞') "
                + "FROM public.plan_profiles ORDER BY plan_type", String.class))
                .containsExactly("FREE:1/500/2", "PREMIUM:∞/∞/∞", "PREMIUM_TRIAL:∞/∞/∞");
        for (String type : List.of("PREMIUM_TRIAL", "PREMIUM")) {
            assertThat(jdbc.queryForList("SELECT module_id FROM public.profile_modules WHERE plan_type = ? "
                    + "AND granted ORDER BY module_id", String.class, type)).containsExactlyElementsOf(PAID);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.profile_modules WHERE plan_type = 'FREE' "
                + "AND granted", Integer.class)).isZero();
        assertThat(planProfiles.profile(PlanType.FREE).maxEmployees()).isEqualTo(2);
        assertThat(planProfiles.profile(PlanType.FREE).fallback()).isFalse();

        MvcResult list = mockMvc.perform(get("/api/v1/admin/plan-profiles")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(body(list)).contains("\"label\":\"Gratuit\"").contains("\"label\":\"Essai Premium\"")
                .contains("\"displayName\":\"Facturation\"").contains("\"fallback\":false")
                .doesNotContain("\"fallback\":true");
    }

    @Test
    @DisplayName("non-super-admin → 403 on GET and PUT, nothing saved")
    void ownerForbidden() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/admin/plan-profiles")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(403);
        assertThat(putProfile("FREE", profile(5, 500, 2, List.of()), ownerToken).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(planProfiles.profile(PlanType.FREE).maxStores()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalid module or limit → 422 French message naming modules by display name, nothing saved")
    void validation() throws Exception {
        MvcResult crm = putProfile("FREE", profile(1, 500, 2, List.of("crm")), adminToken);
        assertThat(crm.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(crm)).contains("PLAN_PROFILE_INVALID").contains("« Clients et fournisseurs » est un module gratuit");
        MvcResult unknown = putProfile("PREMIUM", profile(null, null, null, List.of("exports", "inconnu")), adminToken);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(unknown)).contains("n'existe pas").doesNotContain("inconnu\"");
        for (Integer bad : new Integer[] {0, -1}) {
            MvcResult r = putProfile("FREE", profile(1, bad, 2, List.of()), adminToken);
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
            assertThat(body(r)).contains("supérieur à zéro");
        }
        assertThat(putProfile("GOLD", profile(1, 1, 1, List.of()), adminToken).getResponse().getStatus())
                .isEqualTo(422);
        // a limit key omitted (or misspelt) never means « unlimited »: refused, nothing saved
        Map<String, Object> missing = profile(1, 500, 2, List.of());
        missing.remove("maxProducts");
        MvcResult omitted = putProfile("FREE", missing, adminToken);
        assertThat(omitted.getResponse().getStatus()).as(body(omitted)).isEqualTo(422);
        assertThat(body(omitted)).contains("plafond de produits");
        Map<String, Object> misspelt = profile(1, 500, 2, List.of());
        misspelt.remove("maxProducts");
        misspelt.put("maxProduct", 900);
        MvcResult unknownKey = putProfile("FREE", misspelt, adminToken);
        assertThat(unknownKey.getResponse().getStatus()).as(body(unknownKey)).isEqualTo(422);
        assertThat(body(unknownKey)).contains("champ non reconnu");
        assertThat(planProfiles.profile(PlanType.FREE))
                .satisfies(p -> assertThat(List.of(p.maxStores(), p.maxProducts(), p.maxEmployees()))
                        .containsExactly(1, 500, 2));
        assertThat(planProfiles.profile(PlanType.PREMIUM).modules()).containsExactlyInAnyOrderElementsOf(PAID);
    }

    @Test
    @DisplayName("raise the Free store limit → a 2nd store at once (no restart); lower it back → both stores kept, "
            + "the 3rd refused with the French limit message (limit 1, current 2)")
    void storeLimitLiveChange() throws Exception {
        plan(schema, tenantId, "FREE", "NULL");
        assertThat(stores()).isEqualTo(1);
        MvcResult refused = createStore("Boutique refusée");
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);

        MvcResult raised = putProfile("free", profile(2, 500, 2, List.of()), adminToken); // case-folded
        assertThat(raised.getResponse().getStatus()).as(body(raised)).isEqualTo(200);
        assertThat(body(raised)).contains("\"compositionChanged\":false").contains("\"resync\":null");
        MvcResult second = createStore("Boutique Akwa");
        assertThat(second.getResponse().getStatus()).as(body(second)).isEqualTo(201);

        putProfile("FREE", profile(1, 500, 2, List.of()), adminToken);
        MvcResult third = createStore("Boutique Bonaberi");
        assertThat(third.getResponse().getStatus()).as(body(third)).isEqualTo(403);
        assertThat(body(third)).contains("PLAN_LIMIT_EXCEEDED").contains("Limite de votre plan atteinte")
                .contains("\"limit\":1").contains("\"current\":2");
        assertThat(stores()).isEqualTo(2); // the store above the lowered limit is kept

        MvcResult me = mockMvc.perform(get("/api/v1/subscription/me")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(body(me)).contains("\"maxStores\":1").contains("\"maxEmployees\":2");
    }

    @Test
    @DisplayName("profile row missing → built-in defaults (FREE 1 store, 2 employees), no error")
    void missingRowFallsBack() throws Exception {
        plan(schema, tenantId, "FREE", "NULL");
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type = 'FREE'"); // restored by restoreSeed()
        assertThat(planProfiles.profile(PlanType.FREE).fallback()).isTrue();
        MvcResult list = mockMvc.perform(get("/api/v1/admin/plan-profiles")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(body(list)).contains("\"fallback\":true");

        MvcResult refused = createStore("Boutique fallback");
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(body(refused)).contains("\"limit\":1");
        MvcResult me = mockMvc.perform(get("/api/v1/subscription/me")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me)).contains("\"maxEmployees\":2").contains("\"maxProducts\":500");
    }

    @Test
    @DisplayName("grant exports to Free → the Free tenant's exports PLAN row ACTIVE/FREE without expiry, ENABLED, "
            + "reported by tenant code; removed again → DISABLED")
    void grantExportsToFree() throws Exception {
        plan(schema, tenantId, "FREE", "NULL");
        assertThat(row(tenantId, "exports")).startsWith("DISABLED/");

        MvcResult granted = putProfile("FREE", profile(1, 500, 2, List.of("exports")), adminToken);
        assertThat(granted.getResponse().getStatus()).as(body(granted)).isEqualTo(200);
        assertThat(body(granted)).contains("\"compositionChanged\":true").contains(tenantCode)
                .contains("\"displayName\":\"Exports PDF et Excel\"");
        assertThat(row(tenantId, "exports")).isEqualTo("ACTIVE/FREE/PLAN/-");
        assertThat(row(tenantId, "invoicing")).startsWith("DISABLED/");
        registry.evict(schema);
        assertThat(registry.moduleState(schema, "exports")).isEqualTo(ModuleState.ENABLED);

        putProfile("FREE", profile(1, 500, 2, List.of()), adminToken);
        assertThat(row(tenantId, "exports")).startsWith("DISABLED/").contains("/PLAN/");
    }

    @Test
    @DisplayName("remove invoicing from Premium → Premium tenants' PLAN invoicing rows DISABLED, an ADMIN invoicing "
            + "row untouched, other plan types not re-synced; restored → ACTIVE again")
    void removeInvoicingFromPremium() throws Exception {
        plan(schema, tenantId, "PREMIUM", "now() + interval '200 days'");
        String adminSchema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID adminTenant = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class,
                adminSchema);
        extraTenants.add(adminTenant);
        plan(adminSchema, adminTenant, "PREMIUM", "now() + interval '200 days'");
        jdbc.update("UPDATE public.tenant_modules SET origin = 'ADMIN', tier = 'STANDARD' "
                + "WHERE tenant_id = ? AND module_id = 'invoicing'", adminTenant);
        String adminRow = row(adminTenant, "invoicing");
        String freeSchema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID freeTenant = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class,
                freeSchema);
        extraTenants.add(freeTenant);
        String freeCode = jdbc.queryForObject("SELECT code FROM public.tenants WHERE id = ?", String.class, freeTenant);
        plan(freeSchema, freeTenant, "FREE", "NULL");

        List<String> withoutInvoicing = PAID.stream().filter(m -> !m.equals("invoicing")).toList();
        MvcResult r = putProfile("PREMIUM", profile(null, null, null, withoutInvoicing), adminToken);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(body(r)).contains(tenantCode).doesNotContain(freeCode);

        assertThat(row(tenantId, "invoicing")).startsWith("DISABLED/FREE/PLAN");
        assertThat(row(tenantId, "exports")).startsWith("ACTIVE/PREMIUM/PLAN");
        assertThat(row(adminTenant, "invoicing")).isEqualTo(adminRow);
        registry.evict(schema);
        assertThat(registry.moduleState(schema, "invoicing")).isEqualTo(ModuleState.DISABLED);

        putProfile("PREMIUM", profile(null, null, null, PAID), adminToken);
        assertThat(row(tenantId, "invoicing")).startsWith("ACTIVE/PREMIUM/PLAN");
    }

    private MvcResult createEmployee(UUID storeId) throws Exception {
        String phone = "+2376" + String.format("%08d", (long) (Math.random() * 100_000_000L));
        return mockMvc.perform(post("/api/v1/employees").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("firstName", "Awa", "lastName", "Test",
                        "phoneNumber", phone, "storeId", storeId.toString())))).andReturn();
    }

    private int activeEmployees() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'",
                Integer.class);
    }

    @Test
    @DisplayName("employees through the live profile: 3 active above a lowered limit of 2 are kept; creating or "
            + "reactivating one more is refused (limit 2, current 3)")
    void employeeLimitLowered() throws Exception {
        plan(schema, tenantId, "FREE", "NULL");
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        profileAdmin.update(PlanType.FREE, 1, 500, 4, Set.of(), adminId);
        for (int i = 0; i < 4; i++) {
            MvcResult created = createEmployee(storeId);
            assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        }
        UUID inactive = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".employees LIMIT 1", UUID.class);
        MvcResult deactivated = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/employees/{id}/deactivate", inactive)
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(deactivated.getResponse().getStatus()).as(body(deactivated)).isBetween(200, 299);
        assertThat(activeEmployees()).isEqualTo(3);

        MvcResult lowered = putProfile("FREE", profile(1, 500, 2, List.of()), adminToken);
        assertThat(lowered.getResponse().getStatus()).as(body(lowered)).isEqualTo(200);

        MvcResult refused = createEmployee(storeId);
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(body(refused)).contains("PLAN_LIMIT_EXCEEDED").contains("\"limit\":2").contains("\"current\":3")
                .contains("\"plan\":\"FREE\"");
        MvcResult reactivation = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/employees/{id}/reactivate", inactive)
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(reactivation.getResponse().getStatus()).as(body(reactivation)).isEqualTo(403);
        assertThat(body(reactivation)).contains("PLAN_LIMIT_EXCEEDED").contains("\"limit\":2");
        assertThat(activeEmployees()).isEqualTo(3); // the employees above the lowered limit are kept
    }
}
