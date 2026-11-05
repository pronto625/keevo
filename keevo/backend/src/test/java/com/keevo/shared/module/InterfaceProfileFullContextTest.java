package com.keevo.shared.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.13 — interface profile on the real chain (production security filter, real services, PostgreSQL with
 * V50): the modules list shape and {@code meta.navigationPreset}, the owner toggle (matrix, roles, audit), the owner
 * choice surviving plan changes, an owner-disabled {@code pos} refused on REST and sync push, the onboarding rules
 * (DOCUMENT, sector sets) and their super-admin editing.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("19.13 — interface profile: owner activation, navigation preset, activation rules (full context)")
class InterfaceProfileFullContextTest extends FullContextIntegrationTest {

    private static final String RULES = "/api/v1/admin/module-activation-rules";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;
    @Autowired PlanModuleRowSync planRows;

    private final List<String> schemas = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private UUID ownerId;
    private String owner;
    private String admin;

    @BeforeEach
    void setUp() {
        schema = newTenant();
        tenantId = tenantId(schema);
        ownerId = UUID.randomUUID();
        owner = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        admin = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), "public", "SUPER_ADMIN");
    }

    @AfterEach
    void tearDown() {
        for (String s : schemas) {
            jdbc.update("DELETE FROM public.tenant_module_activation WHERE tenant_id = ?", tenantId(s));
            registry.evict(s);
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

    private UUID tenantId(String s) {
        return jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s);
    }

    private void preferences(String s) {
        jdbc.update("INSERT INTO \"" + s + "\".tenant_preferences DEFAULT VALUES");
    }

    private String member(String role) {
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ndiaye', false)", userId, storeId);
        return TestAccess.token(jdbc, jwtTokenProvider, userId, schema, role, "ACTIVE", storeId, false);
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r));
    }

    private JsonNode error(MvcResult r, int status, String domainCode) throws Exception {
        JsonNode json = json(r, status);
        assertThat(json.path("domainCode").asText()).as(body(r)).isEqualTo(domainCode);
        return json;
    }

    private MvcResult toggle(String token, String moduleId, Object activated) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("activated", activated);
        return call(token, HttpMethod.PUT, "/api/v1/tenants/me/modules/" + moduleId + "/activation", b);
    }

    private JsonNode modules(String token) throws Exception {
        return json(call(token, HttpMethod.GET, "/api/v1/tenants/me/modules", null), 200);
    }

    private static JsonNode module(JsonNode list, String moduleId) {
        for (JsonNode m : list.path("data")) {
            if (m.path("moduleId").asText().equals(moduleId)) return m;
        }
        throw new AssertionError("module " + moduleId + " not listed");
    }

    private Map<String, Boolean> ownerRows(String s) {
        Map<String, Boolean> rows = new LinkedHashMap<>();
        jdbc.query("SELECT module_id, activated FROM public.tenant_module_activation WHERE tenant_id = ? "
                + "ORDER BY module_id", rs -> {
            rows.put(rs.getString(1), rs.getBoolean(2));
        }, tenantId(s));
        return rows;
    }

    private int ownerAudits(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE action = ? "
                + "AND entity_type = 'Tenant'", Integer.class, action);
    }

    private void plan(String planType) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', "
                + "expires_at = now() + interval '30 days'", planType);
        planRows.sync(tenantId, schema);
        registry.evict(schema);
    }

    private void complete(String token, String sectorType, String saleFlow) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sectorType", sectorType);
        b.put("storeName", "Boutique Profil");
        if (saleFlow != null) b.put("defaultSaleFlow", saleFlow);
        MvcResult r = call(token, HttpMethod.POST, "/api/v1/onboarding/complete", b);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    }

    private static Map<String, Object> op(String type, Map<String, Object> payload) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", UUID.randomUUID().toString());
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().toString());
        return op;
    }

    private Map<String, Object> ruleSet(String flow, String sector, List<String> modules) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("saleFlow", flow);
        b.put("sectorType", sector);
        b.put("disabledModules", modules);
        return b;
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: every module has inPlan / activated / ownerToggleable; meta.navigationPreset CAISSE; "
            + "preferences carry navigationPreset as last field")
    void listShape() throws Exception {
        preferences(schema);
        JsonNode list = modules(member("EMPLOYEE"));
        assertThat(list.path("meta").path("navigationPreset").asText()).isEqualTo("CAISSE");
        for (JsonNode m : list.path("data")) {
            assertThat(m.has("inPlan") && m.has("activated") && m.has("ownerToggleable")).as(m.toString()).isTrue();
            assertThat(m.path("activated").asBoolean()).isTrue();
        }
        assertThat(module(list, "core").path("ownerToggleable").asBoolean()).isFalse();
        assertThat(module(list, "store").path("ownerToggleable").asBoolean()).isFalse();
        assertThat(module(list, "invoicing").path("ownerToggleable").asBoolean()).isTrue();
        assertThat(module(list, "invoicing").path("inPlan").asBoolean()).isTrue(); // registration trial

        JsonNode prefs = json(call(owner, HttpMethod.GET, "/api/v1/tenant/preferences", null), 200).path("data");
        assertThat(prefs.path("navigationPreset").asText()).isEqualTo("CAISSE");
        List<String> fields = new ArrayList<>();
        prefs.fieldNames().forEachRemaining(fields::add);
        assertThat(fields.get(fields.size() - 1)).isEqualTo("navigationPreset");

        jdbc.update("UPDATE \"" + schema + "\".tenant_preferences SET default_sale_flow = 'DOCUMENT'");
        assertThat(modules(owner).path("meta").path("navigationPreset").asText()).isEqualTo("DOCUMENT");
    }

    @Test
    @DisplayName("owner toggle matrix: disable invoicing (audited, 403 MODULE_DISABLED_BY_OWNER), same value (no "
            + "audit), re-enable; not toggleable, unknown, dependents, requirement off, body, roles")
    void ownerToggle() throws Exception {
        JsonNode off = json(toggle(owner, "invoicing", false), 200).path("data");
        assertThat(off.path("state").asText()).isEqualTo("DISABLED");
        assertThat(off.path("activated").asBoolean()).isFalse();
        assertThat(off.path("inPlan").asBoolean()).isTrue();
        assertThat(ownerRows(schema)).containsEntry("invoicing", false);
        assertThat(ownerAudits("MODULE_DISABLED_BY_OWNER")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entity_label FROM \"" + schema + "\".audit_log WHERE action = "
                + "'MODULE_DISABLED_BY_OWNER'", String.class)).isEqualTo("Facturation");
        JsonNode refused = error(call(owner, HttpMethod.GET, "/api/v1/invoices", null), 403,
                "MODULE_DISABLED_BY_OWNER");
        assertThat(refused.path("error").asText())
                .isEqualTo("Ce module est désactivé : le propriétaire peut l'activer dans Paramètres › Mes modules");

        json(toggle(owner, "invoicing", false), 200);
        assertThat(ownerAudits("MODULE_DISABLED_BY_OWNER")).isEqualTo(1);

        JsonNode on = json(toggle(owner, "invoicing", true), 200).path("data");
        assertThat(on.path("state").asText()).isEqualTo("ENABLED");
        assertThat(ownerAudits("MODULE_ENABLED_BY_OWNER")).isEqualTo(1);
        assertThat(call(owner, HttpMethod.GET, "/api/v1/invoices", null).getResponse().getStatus()).isEqualTo(200);

        for (String id : List.of("core", "sync", "store", "messaging")) {
            assertThat(error(toggle(owner, id, false), 409, "MODULE_NOT_TOGGLEABLE").path("error").asText())
                    .isEqualTo("Ce module ne peut pas être désactivé");
        }
        error(toggle(owner, "foo", true), 404, "MODULE_NOT_FOUND");
        assertThat(error(toggle(owner, "inventory", false), 409, "MODULE_HAS_DEPENDENTS").path("error").asText())
                .startsWith("Désactivez d'abord : ").contains("Point de vente");
        for (String id : List.of("pos", "counting", "multi-store", "inventory")) {
            json(toggle(owner, id, false), 200);
        }
        assertThat(error(toggle(owner, "pos", true), 409, "MODULE_REQUIREMENT_DISABLED").path("error").asText())
                .isEqualTo("Activez d'abord : Stock et inventaire");
        assertThat(error(toggle(owner, "invoicing", null), 422, "VALIDATION_ERROR").path("error").asText())
                .isEqualTo("Indiquez si le module est activé");
        for (String role : List.of("MANAGER", "EMPLOYEE", "VIEWER")) {
            error(toggle(member(role), "invoicing", false), 403, "FORBIDDEN");
        }
        assertThat(ownerRows(schema)).containsEntry("invoicing", true);
    }

    @Test
    @DisplayName("FREE owner activating a module outside the plan → 409 MODULE_NOT_IN_PLAN")
    void notInPlan() throws Exception {
        plan("FREE");
        assertThat(error(toggle(owner, "exports", true), 409, "MODULE_NOT_IN_PLAN").path("error").asText())
                .isEqualTo("Ce module n'est pas compris dans votre plan : voyez Mon plan");
        JsonNode invoicing = module(modules(owner), "invoicing");
        assertThat(invoicing.path("inPlan").asBoolean()).as("V50: FREE grants invoicing").isTrue();
    }

    @Test
    @DisplayName("plan change (TEAM → PRO → FREE → TEAM) keeps the owner's choice on exports")
    void planChangeKeepsOwnerChoice() throws Exception {
        plan("TEAM");
        json(toggle(owner, "exports", false), 200);
        for (String p : List.of("PRO", "FREE", "TEAM")) {
            plan(p);
            JsonNode exports = module(modules(owner), "exports");
            assertThat(exports.path("activated").asBoolean()).as(p).isFalse();
            assertThat(exports.path("state").asText()).as(p).isEqualTo("DISABLED");
            assertThat(exports.path("inPlan").asBoolean()).as(p).isEqualTo(!p.equals("FREE"));
        }
        assertThat(ownerRows(schema)).containsEntry("exports", false);
    }

    @Test
    @DisplayName("owner-disabled pos: REST 403 MODULE_DISABLED_BY_OWNER, sync push REJECTED / MODULE_INACTIVE")
    void posDisabledByOwner() throws Exception {
        for (String id : List.of("pos", "counting", "multi-store")) {
            json(toggle(owner, id, false), 200);
        }
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        error(call(owner, HttpMethod.GET, "/api/v1/day-closures?storeId=" + storeId, null), 403,
                "MODULE_DISABLED_BY_OWNER");
        MvcResult r = call(owner, HttpMethod.POST, "/api/v1/sync/push", Map.of("deviceId", "device-1913",
                "operations", List.of(op("CANCEL_SALE", Map.of("saleId", UUID.randomUUID().toString(),
                        "reason", "Erreur de saisie client")))));
        JsonNode result = json(r, 200).path("data").path("results").get(0);
        assertThat(result.path("status").asText()).isEqualTo("REJECTED");
        assertThat(result.path("reason").asText()).isEqualTo("MODULE_INACTIVE");
    }

    @Test
    @DisplayName("onboarding with DOCUMENT → preset DOCUMENT, pos / inventory / counting / multi-store disabled; "
            + "POS (absent) → only invoicing disabled; unknown flow → 422")
    void onboardingRules() throws Exception {
        complete(owner, "CLOTHING", "DOCUMENT");
        assertThat(ownerRows(schema)).containsExactlyInAnyOrderEntriesOf(Map.of("counting", false,
                "inventory", false, "multi-store", false, "pos", false));
        JsonNode list = modules(owner);
        assertThat(list.path("meta").path("navigationPreset").asText()).isEqualTo("DOCUMENT");
        assertThat(module(list, "pos").path("activated").asBoolean()).isFalse();
        assertThat(module(list, "pos").path("state").asText()).isEqualTo("DISABLED");
        assertThat(module(list, "catalog").path("activated").asBoolean()).isTrue();

        String other = newTenant();
        String otherOwner = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), other, "OWNER");
        MvcResult bad = call(otherOwner, HttpMethod.POST, "/api/v1/onboarding/complete",
                Map.of("sectorType", "CLOTHING", "storeName", "Boutique Profil", "defaultSaleFlow", "SHOP"));
        assertThat(error(bad, 422, "VALIDATION_ERROR").path("error").asText())
                .isEqualTo("Parcours de vente inconnu : choisissez Caisse ou Documents");
        complete(otherOwner, "CLOTHING", null);
        assertThat(ownerRows(other)).as("POS default set: invoicing off (no automatic invoice per till sale)")
                .containsExactly(Map.entry("invoicing", false));
        assertThat(jdbc.queryForObject("SELECT default_sale_flow FROM \"" + other + "\".tenant_preferences",
                String.class)).isEqualTo("POS");
    }

    @Test
    @DisplayName("super-admin rules: GET the defaults, PUT an edited DOCUMENT set (applied exactly to a new tenant, "
            + "existing ones untouched), a sector set wins, validation 422, defaults not deletable 409, DELETE")
    void adminRules() throws Exception {
        JsonNode sets = json(call(admin, HttpMethod.GET, RULES, null), 200).path("data");
        JsonNode documentDefault = null;
        for (JsonNode s : sets) {
            if (s.path("saleFlow").asText().equals("DOCUMENT") && s.path("sectorType").isNull()) documentDefault = s;
        }
        assertThat(documentDefault).isNotNull();
        List<String> defaults = new ArrayList<>();
        documentDefault.path("disabledModules").forEach(m -> defaults.add(m.path("moduleId").asText()));
        assertThat(defaults).containsExactly("counting", "inventory", "multi-store", "pos");
        assertThat(documentDefault.path("defaultSet").asBoolean()).isTrue();
        assertThat(documentDefault.path("saleFlowLabel").asText()).isEqualTo("Documents");

        try {
            JsonNode edited = json(call(admin, HttpMethod.PUT, RULES, ruleSet("DOCUMENT", null,
                    List.of("pos", "inventory", "counting", "multi-store", "invoicing"))), 200).path("data");
            assertThat(edited.path("disabledModules")).hasSize(5);
            JsonNode sector = json(call(admin, HttpMethod.PUT, RULES, ruleSet("DOCUMENT", "PHARMACY",
                    List.of("exports"))), 201).path("data");
            assertThat(sector.path("defaultSet").asBoolean()).isFalse();

            complete(owner, "CLOTHING", "DOCUMENT");
            assertThat(ownerRows(schema).keySet()).containsExactlyInAnyOrder("counting", "inventory", "invoicing",
                    "multi-store", "pos");

            String pharmacy = newTenant();
            complete(TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), pharmacy, "OWNER"), "PHARMACY",
                    "DOCUMENT");
            assertThat(ownerRows(pharmacy)).containsExactly(Map.entry("exports", false));

            String untouched = newTenant();
            assertThat(ownerRows(untouched)).isEmpty();

            assertThat(error(call(admin, HttpMethod.PUT, RULES, ruleSet("DOCUMENT", null, List.of("inventory"))), 422,
                    "MODULE_ACTIVATION_RULE_INVALID").path("error").asText()).startsWith("Règle non enregistrée : ");
            error(call(admin, HttpMethod.PUT, RULES, ruleSet("POS", "BAKERY", List.of())), 422,
                    "MODULE_ACTIVATION_RULE_INVALID");
            error(call(admin, HttpMethod.PUT, RULES, ruleSet("POS", null, List.of("store"))), 422,
                    "MODULE_ACTIVATION_RULE_INVALID");
            error(call(admin, HttpMethod.PUT, RULES, Map.of("saleFlow", "POS", "disabledModules", List.of())), 422,
                    "MODULE_ACTIVATION_RULE_INVALID");
            error(call(admin, HttpMethod.DELETE, RULES + "?saleFlow=DOCUMENT", null), 409, "INVALID_STATE");
            error(call(owner, HttpMethod.GET, RULES, null), 403, "FORBIDDEN");

            MvcResult deleted = call(admin, HttpMethod.DELETE, RULES + "?saleFlow=DOCUMENT&sectorType=PHARMACY", null);
            assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
            error(call(admin, HttpMethod.DELETE, RULES + "?saleFlow=DOCUMENT&sectorType=PHARMACY", null), 404,
                    "NOT_FOUND");
            assertThat(ownerRows(pharmacy)).as("existing tenants untouched").containsExactly(Map.entry("exports", false));
        } finally {
            jdbc.update("DELETE FROM public.module_activation_rule_sets WHERE sector_type IS NOT NULL");
            json(call(admin, HttpMethod.PUT, RULES, ruleSet("DOCUMENT", null, defaults)), 200);
        }
    }
}
