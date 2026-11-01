package com.keevo.admin.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 16.2 — bundles end to end in the real application context on PostgreSQL: the V34 seed « Pack Pro », the
 * super-admin API (list / define / delete, 403 / 404 / 422), and the application of a bundle to a Free tenant through
 * the module lifecycle (one activation, per-module ADMIN rows only, TRIAL default of 14 days, all-or-nothing on a
 * refused validation, modules kept after the bundle is deleted). Every test removes the rows and bundles it wrote.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Bundles — full context (Story 16.2)")
class BundlePostgresIntegrationTest extends FullContextIntegrationTest {

    private static final String TEST_BUNDLE = "test-pack-1602";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired ModuleRegistry registry;

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
        adminToken = jwtTokenProvider.generateAccessToken(adminId, "public", "SUPER_ADMIN");
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        // a Free tenant: paid modules disabled by the plan, free modules enabled
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', status = 'ACTIVE', "
                + "expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        rowSync.sync(tenantId, schema);
        registry.evict(schema);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
        jdbc.update("DELETE FROM public.bundle_definitions WHERE id = ?", TEST_BUNDLE);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MvcResult apply(String bundleId, Map<String, Object> body, String token) throws Exception {
        var request = post("/api/v1/admin/tenants/{t}/bundles/{b}", tenantId, bundleId)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult putBundle(String bundleId, Map<String, Object> body, String token) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/bundles/{b}", bundleId).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private static Map<String, Object> definition(String name, String description, List<String> modules) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", description);
        body.put("modules", modules);
        return body;
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r)).get("data");
    }

    private static List<String> names(JsonNode refs) {
        List<String> out = new ArrayList<>();
        refs.forEach(n -> out.add(n.get("displayName").asText()));
        return out;
    }

    /** status/origin/expires_at of a tenant_modules row, or null. */
    private String row(String moduleId) {
        List<String> rows = jdbc.queryForList("SELECT status || '/' || origin FROM public.tenant_modules "
                + "WHERE tenant_id = ? AND module_id = ?", String.class, tenantId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Every column of the rows of the bundle's free modules (absent = no row). */
    private Map<String, String> freeRows() {
        Map<String, String> out = new HashMap<>();
        for (String id : List.of("pos", "inventory", "crm")) {
            out.put(id, String.valueOf(jdbc.queryForList("SELECT * FROM public.tenant_modules WHERE tenant_id = ? "
                    + "AND module_id = ?", tenantId, id)));
        }
        return out;
    }

    private Instant expiry(String moduleId) {
        return jdbc.queryForObject("SELECT expires_at FROM public.tenant_modules WHERE tenant_id = ? AND module_id = ?",
                java.sql.Timestamp.class, tenantId, moduleId).toInstant();
    }

    private Map<String, String> tenantModuleStates() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/admin/tenants/{t}/modules", tenantId)
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        Map<String, String> states = new HashMap<>();
        data(r).forEach(n -> states.put(n.get("moduleId").asText(), n.get("state").asText()));
        return states;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("seed: « Pack Pro » = Point de vente, Stock et inventaire, Facturation, Clients et fournisseurs")
    void seed() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/admin/bundles").header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        JsonNode pro = null;
        for (JsonNode bundle : data(list)) {
            if ("pro".equals(bundle.get("id").asText())) {
                pro = bundle;
            }
        }
        assertThat(pro).isNotNull();
        assertThat(pro.get("name").asText()).isEqualTo("Pack Pro");
        assertThat(names(pro.get("modules"))).containsExactly("Point de vente", "Stock et inventaire", "Facturation",
                "Clients et fournisseurs");
    }

    @Test
    @DisplayName("apply Pack Pro to a Free tenant: Facturation ADMIN ACTIVE with the end date, free modules listed as "
            + "already active and unchanged; applied again → everything already active")
    void applyPackPro() throws Exception {
        assertThat(row("invoicing")).isEqualTo("DISABLED/PLAN");
        Map<String, String> freeRowsBefore = freeRows();
        Map<String, String> before = tenantModuleStates();
        assertThat(before.get("invoicing")).isEqualTo("DISABLED");

        MvcResult first = apply("pro", Map.of("status", "ACTIVE", "expiresAt", "2027-06-30"), adminToken);

        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        assertThat(data(first).get("name").asText()).isEqualTo("Pack Pro");
        assertThat(names(data(first).get("activated"))).containsExactly("Facturation");
        assertThat(names(data(first).get("alreadyActive"))).containsExactly("Point de vente", "Stock et inventaire",
                "Clients et fournisseurs");
        assertThat(row("invoicing")).isEqualTo("ACTIVE/ADMIN");
        assertThat(expiry("invoicing")).isEqualTo(Instant.parse("2027-06-30T22:59:59Z"));
        Map<String, String> after = tenantModuleStates();
        assertThat(after.get("invoicing")).isEqualTo("ENABLED");
        before.remove("invoicing");
        after.remove("invoicing");
        assertThat(after).isEqualTo(before); // the free modules (and the rest) unchanged
        assertThat(freeRows()).isEqualTo(freeRowsBefore); // free modules' rows untouched
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenants WHERE id = ? AND plan_type = 'FREE'",
                Integer.class, tenantId)).isEqualTo(1); // a bundle is never a state of the tenant

        MvcResult again = apply("pro", Map.of("status", "ACTIVE", "expiresAt", "2027-06-30"), adminToken);
        assertThat(again.getResponse().getStatus()).as(body(again)).isEqualTo(200);
        assertThat(data(again).get("activated")).isEmpty();
        assertThat(names(data(again).get("alreadyActive"))).containsExactly("Point de vente", "Stock et inventaire",
                "Facturation", "Clients et fournisseurs");
    }

    @Test
    @DisplayName("TRIAL without a date → Facturation TRIAL for 14 days")
    void trialDefault() throws Exception {
        Instant start = Instant.now();
        MvcResult r = apply("pro", Map.of("status", "TRIAL"), adminToken);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        assertThat(row("invoicing")).isEqualTo("TRIAL/ADMIN");
        assertThat(expiry("invoicing")).isBetween(start.plus(Duration.ofDays(14)).minusSeconds(5),
                Instant.now().plus(Duration.ofDays(14)).plusSeconds(5));
    }

    @Test
    @DisplayName("ACTIVE without a date on a bundle with a paid module → 422, no row written for any module")
    void validationWritesNothing() throws Exception {
        Map<String, String> freeRowsBefore = freeRows();
        MvcResult r = apply("pro", null, adminToken);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains("« Facturation »");
        assertThat(row("invoicing")).isEqualTo("DISABLED/PLAN");
        assertThat(freeRows()).isEqualTo(freeRowsBefore);
    }

    @Test
    @DisplayName("define a bundle with a shared dependency, apply it, delete it: tenant keeps its modules; then 404")
    void defineApplyDelete() throws Exception {
        MvcResult created = putBundle(TEST_BUNDLE, definition("Pack Stock", "", List.of("counting", "multi-store")),
                adminToken);
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        assertThat(data(created).get("description").isNull()).isTrue();
        MvcResult replaced = putBundle(TEST_BUNDLE, definition("Pack Stock+", "Stock avancé",
                List.of("multi-store", "counting")), adminToken);
        assertThat(replaced.getResponse().getStatus()).as(body(replaced)).isEqualTo(200);
        assertThat(names(data(replaced).get("modules"))).containsExactly("Multi-boutiques et transferts",
                "Inventaire assisté");

        MvcResult applied = apply(TEST_BUNDLE, Map.of("status", "TRIAL", "expiresAt", "2027-01-31"), adminToken);
        assertThat(applied.getResponse().getStatus()).as(body(applied)).isEqualTo(200);
        // inventory (shared dependency, free, enabled) needs nothing: only the two paid modules are written
        assertThat(names(data(applied).get("activated"))).containsExactlyInAnyOrder("Inventaire assisté",
                "Multi-boutiques et transferts");
        assertThat(row("counting")).isEqualTo("TRIAL/ADMIN");
        assertThat(row("multi-store")).isEqualTo("TRIAL/ADMIN");

        MvcResult deleted = mockMvc.perform(delete("/api/v1/admin/bundles/{b}", TEST_BUNDLE)
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
        MvcResult list = mockMvc.perform(get("/api/v1/admin/bundles").header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(body(list)).doesNotContain(TEST_BUNDLE);
        assertThat(row("counting")).isEqualTo("TRIAL/ADMIN"); // kept
        assertThat(tenantModuleStates().get("counting")).isEqualTo("ENABLED");

        MvcResult again = mockMvc.perform(delete("/api/v1/admin/bundles/{b}", TEST_BUNDLE)
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(404);
        MvcResult gone = apply(TEST_BUNDLE, Map.of("status", "TRIAL"), adminToken);
        assertThat(gone.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(gone)).contains("Pack introuvable");
    }

    @Test
    @DisplayName("stale pack (unknown module, core-category module inserted directly) → GET applicable=false and "
            + "« Module indisponible »; apply → 422 with the French message, no tenant_modules row written")
    void stalePack() throws Exception {
        jdbc.update("INSERT INTO public.bundle_definitions (id, name) VALUES (?, 'Pack Ancien')", TEST_BUNDLE);
        jdbc.update("INSERT INTO public.bundle_modules (bundle_id, module_id, position) VALUES (?, 'invoicing', 0), "
                + "(?, 'retired-module', 1), (?, 'sync', 2)", TEST_BUNDLE, TEST_BUNDLE, TEST_BUNDLE);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList(
                "SELECT * FROM public.tenant_modules WHERE tenant_id = ? ORDER BY module_id", tenantId);

        MvcResult list = mockMvc.perform(get("/api/v1/admin/bundles").header("Authorization", "Bearer " + adminToken))
                .andReturn();
        JsonNode stale = null;
        for (JsonNode bundle : data(list)) {
            if (TEST_BUNDLE.equals(bundle.get("id").asText())) {
                stale = bundle;
            } else if ("pro".equals(bundle.get("id").asText())) {
                assertThat(bundle.get("applicable").asBoolean()).isTrue();
            }
        }
        assertThat(stale).isNotNull();
        assertThat(stale.get("applicable").asBoolean()).isFalse();
        assertThat(names(stale.get("modules"))).containsExactly("Facturation", "Module indisponible",
                "Module indisponible");

        MvcResult r = apply(TEST_BUNDLE, Map.of("status", "TRIAL"), adminToken);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
        assertThat(objectMapper.readTree(body(r)).get("error").asText()).isEqualTo("Le pack « Pack Ancien » contient "
                + "un module qui n'est plus disponible. Modifiez-le avant de l'appliquer.");
        assertThat(jdbc.queryForList("SELECT * FROM public.tenant_modules WHERE tenant_id = ? ORDER BY module_id",
                tenantId)).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("unknown bundle → 404 « Pack introuvable »")
    void unknownBundle() throws Exception {
        MvcResult r = apply("nope", Map.of("status", "TRIAL"), adminToken);
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(objectMapper.readTree(body(r)).get("error").asText()).isEqualTo("Pack introuvable");
    }

    @Test
    @DisplayName("invalid definitions → 422 French message naming modules by display name, nothing saved")
    void invalidDefinitions() throws Exception {
        MvcResult core = putBundle(TEST_BUNDLE, definition("Pack", null, List.of("pos", "core")), adminToken);
        assertThat(core.getResponse().getStatus()).as(body(core)).isEqualTo(422);
        assertThat(body(core)).contains("BUNDLE_INVALID").contains("« Socle » fait partie du socle");
        MvcResult sync = putBundle(TEST_BUNDLE, definition("Pack", null, List.of("sync")), adminToken);
        assertThat(body(sync)).contains("« Synchronisation hors ligne » fait partie du socle");
        MvcResult platform = putBundle(TEST_BUNDLE, definition("Pack", null, List.of("admin")), adminToken);
        assertThat(platform.getResponse().getStatus()).isEqualTo(422);
        MvcResult unknown = putBundle(TEST_BUNDLE, definition("Pack", null, List.of("pos", "inconnu")), adminToken);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(unknown)).contains("n'existe pas").doesNotContain("inconnu\"");
        MvcResult empty = putBundle(TEST_BUNDLE, definition("Pack", null, List.of()), adminToken);
        assertThat(empty.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(empty)).contains("au moins un module");
        MvcResult duplicate = putBundle(TEST_BUNDLE, definition("Pack", null, List.of("pos", "pos")), adminToken);
        assertThat(body(duplicate)).contains("« Point de vente » figure plusieurs fois");
        MvcResult noName = putBundle(TEST_BUNDLE, definition(" ", null, List.of("pos")), adminToken);
        assertThat(body(noName)).contains("indiquez le nom du pack");
        MvcResult badId = putBundle("Pack_Pro", definition("Pack", null, List.of("pos")), adminToken);
        assertThat(badId.getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.bundle_definitions WHERE id IN (?, 'pack_pro')",
                Integer.class, TEST_BUNDLE)).isZero();
    }

    @Test
    @DisplayName("OWNER token → 403 on every bundle endpoint, nothing written")
    void ownerForbidden() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/admin/bundles").header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(putBundle(TEST_BUNDLE, definition("Pack", null, List.of("pos")), ownerToken).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(delete("/api/v1/admin/bundles/{b}", "pro").header("Authorization",
                "Bearer " + ownerToken)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(apply("pro", Map.of("status", "TRIAL"), ownerToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(row("invoicing")).isEqualTo("DISABLED/PLAN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.bundle_definitions WHERE id = 'pro'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("activation state is real: Facturation readable after the bundle via the registry")
    void registryState() throws Exception {
        apply("pro", Map.of("status", "TRIAL"), adminToken);
        registry.evict(schema);
        assertThat(registry.moduleState(schema, "invoicing")).isEqualTo(ModuleState.ENABLED);
    }
}
