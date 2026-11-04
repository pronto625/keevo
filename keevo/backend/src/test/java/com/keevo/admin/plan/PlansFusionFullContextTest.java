package com.keevo.admin.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.subscription.plan.adapter.in.bootstrap.PlanMigrationJob;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsUseCase;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Story 19.7b — the merged offer end to end in the real application context (V43 seed): registration on the
 * 1-month BUSINESS_TRIAL (V1 « PREMIUM_TRIAL », 8 modules in trial, then FREE), the one-shot PREMIUM → TEAM migration
 * job (same expiry, PLAN_MIGRATION event, module rows, idempotent), running PREMIUM_TRIAL trials untouched, FREE
 * grandfathering (3 employees kept, a 4th refused), the per-store employee limit of PRO, the admin activation with a
 * plan parameter (default TEAM) and the {@code expiresAt} / plan-kind rule of the legacy activate endpoint.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Fusion plans — FREE/PRO/TEAM/BUSINESS, PREMIUM → TEAM, 1-month trial (Story 19.7b)")
class PlansFusionFullContextTest extends FullContextIntegrationTest {

    private static final List<String> PAID_MODULES = List.of("advanced-reports", "alerts", "counting", "exports",
            "invoicing", "multi-store", "profitability", "report-config");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired DowngradeExpiredTrialsUseCase expiryJob;
    @Autowired PlanMigrationJob migrationJob;
    @Autowired com.keevo.shared.module.application.port.PlanCatalogStore catalogStore;

    private static final String MARKER = "plan-migration-premium-team";

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<UUID> tenants = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String adminToken;
    private String ownerToken;
    private UUID storeA;

    @BeforeEach
    void setUp() {
        schema = register();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        adminToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, adminId,
                "public", "SUPER_ADMIN");
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId,
                schema, "OWNER");
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
    }

    @AfterEach
    void cleanUp() {
        for (UUID tenant : tenants) {
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_payments WHERE tenant_id = ?", tenant);
        }
        for (String phone : phones) {
            jdbc.update("DELETE FROM public.user_tenant_memberships WHERE user_id IN "
                    + "(SELECT id FROM public.users WHERE phone_number = ?)", phone);
            jdbc.update("DELETE FROM public.users WHERE phone_number = ?", phone);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String register() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenants.add(jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s));
        return s;
    }

    private void plan(String code, String expiresSql) {
        plan(schema, tenantId, code, expiresSql);
    }

    private void plan(String s, UUID id, String code, String expiresSql) {
        jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', expires_at = "
                + expiresSql, code);
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", code, id);
        rowSync.sync(id, s);
    }

    /** The job already ran at context start (marker written): forget it so the next run migrates. */
    private void forgetMarker() {
        jdbc.update("DELETE FROM public.data_migrations WHERE name = ?", MARKER);
    }

    private int markers() {
        return jdbc.queryForObject("SELECT count(*) FROM public.data_migrations WHERE name = ?", Integer.class, MARKER);
    }

    private String subscriptionPlan() {
        return jdbc.queryForObject("SELECT plan_type FROM \"" + schema + "\".subscriptions", String.class);
    }

    private JsonNode me() throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/subscription/me")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);
        return objectMapper.readTree(body(me)).path("data");
    }

    private String row(String moduleId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = ?", String.class, tenantId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID newStore(String name) {
        return jdbc.queryForObject("INSERT INTO \"" + schema + "\".stores (name) VALUES (?) RETURNING id", UUID.class,
                name);
    }

    /** An employee row inserted as existing data (grandfathered members). */
    private UUID employee(UUID store, String status) {
        return jdbc.queryForObject("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "status) VALUES (?, ?, 'Awa', 'Mbarga', ?) RETURNING id", UUID.class, UUID.randomUUID(), store, status);
    }

    private MvcResult createEmployee(UUID store) throws Exception {
        String phone = "+2376" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        phones.add(phone);
        return call(post("/api/v1/employees"), ownerToken, Map.of("firstName", "Jean", "lastName", "Ndi",
                "phoneNumber", phone, "storeId", store.toString()));
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object payload) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertRefused(MvcResult r, String... fragments) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
        assertThat(body(r)).contains("PLAN_LIMIT_EXCEEDED").contains(fragments);
    }

    // ── registration: 1-month BUSINESS_TRIAL ─────────────────────────────────

    @Test
    @DisplayName("a new registration gets BUSINESS_TRIAL for 1 month: V1 « PREMIUM_TRIAL », label « Essai Business », "
            + "the 8 paid modules in trial; expiry gives FREE")
    void registrationTrial() throws Exception {
        assertThat(subscriptionPlan()).isEqualTo("BUSINESS_TRIAL");
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM \"" + schema + "\".subscriptions",
                java.sql.Timestamp.class).toInstant();
        assertThat(Duration.between(Instant.now(), expires).toDays()).isBetween(27L, 31L);

        JsonNode me = me();
        assertThat(me.path("planType").asText()).isEqualTo("PREMIUM_TRIAL");
        assertThat(me.path("planCode").asText()).isEqualTo("BUSINESS_TRIAL");
        assertThat(me.path("planLabel").asText()).isEqualTo("Essai Business");
        assertThat(me.path("planKind").asText()).isEqualTo("TRIAL");
        assertThat(me.path("maxStores").isNull()).isTrue();
        assertThat(me.path("maxEmployeesPerStore").isNull()).isTrue();
        for (String module : PAID_MODULES) {
            assertThat(row(module)).as(module).isEqualTo("TRIAL");
        }

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 hour'");
        expiryJob.execute(new DowngradeExpiredTrialsCommand("test"));
        JsonNode free = me();
        assertThat(free.path("planType").asText()).isEqualTo("FREE");
        assertThat(free.path("planLabel").asText()).isEqualTo("Gratuit");
        assertThat(free.path("maxEmployees").asInt()).isEqualTo(1);
        assertThat(free.path("maxEmployeesPerStore").asInt()).isEqualTo(1);
    }

    // ── PREMIUM → TEAM ────────────────────────────────────────────────────────

    @Test
    @DisplayName("migration job: PREMIUM → TEAM with the same expiry, public plan, PLAN_MIGRATION event, module rows "
            + "ACTIVE, V1 still « PREMIUM »; a rerun moves nothing")
    void premiumBecomesTeam() throws Exception {
        plan("PREMIUM", "'2027-01-31T22:59:59Z'");
        Instant expiry = Instant.parse("2027-01-31T22:59:59Z");
        forgetMarker();

        migrationJob.migrateAll();

        assertThat(subscriptionPlan()).isEqualTo("TEAM");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM \"" + schema + "\".subscriptions",
                java.sql.Timestamp.class).toInstant()).isEqualTo(expiry);
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".subscriptions", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("TEAM");
        String events = "SELECT from_plan || '>' || to_plan || ':' || coalesce(reason, '') || ':' "
                + "|| (actor_id IS NULL) FROM public.tenant_plan_events WHERE tenant_id = ? AND cause = 'PLAN_MIGRATION'";
        assertThat(jdbc.queryForList(events, String.class, tenantId))
                .containsExactly("PREMIUM>TEAM:Fusion : Premium devient Team:true");
        for (String module : PAID_MODULES) {
            assertThat(row(module)).as(module).isEqualTo("ACTIVE");
        }
        JsonNode me = me();
        assertThat(me.path("planType").asText()).isEqualTo("PREMIUM");
        assertThat(me.path("planCode").asText()).isEqualTo("TEAM");
        assertThat(me.path("planKind").asText()).isEqualTo("PAID");
        assertThat(me.path("maxStores").asInt()).isEqualTo(8);
        assertThat(me.path("maxEmployeesPerStore").asInt()).isEqualTo(8);

        migrationJob.migrateAll(); // idempotent
        assertThat(jdbc.queryForList(events, String.class, tenantId)).hasSize(1);
        assertThat(subscriptionPlan()).isEqualTo("TEAM");
    }

    @Test
    @DisplayName("migration job: once the done-marker is written, a run reads it and touches no tenant")
    void doneMarkerStopsTheJob() {
        forgetMarker();
        migrationJob.migrateAll();
        if (markers() == 0) { // another test class left a broken tenant: the marker is written by the first clean run
            jdbc.update("INSERT INTO public.data_migrations (name, done_at) VALUES (?, now())", MARKER);
        }
        plan("PREMIUM", "now() + interval '30 days'");

        assertThat(migrationJob.migrateAll()).isZero();
        assertThat(subscriptionPlan()).isEqualTo("PREMIUM");
    }

    @Test
    @DisplayName("migration job guard: TEAM archived or not of kind PAID → nothing migrated, no marker")
    void guardRefusesAnArchivedOrUnpaidTarget() {
        plan("PREMIUM", "now() + interval '30 days'");
        try {
            for (String bad : List.of("archived_at = now()", "kind = 'TRIAL'")) {
                jdbc.update("UPDATE public.plans SET " + bad + " WHERE code = 'TEAM'");
                catalogStore.invalidate();
                forgetMarker();
                assertThat(migrationJob.migrateAll()).as(bad).isZero();
                assertThat(subscriptionPlan()).as(bad).isEqualTo("PREMIUM");
                assertThat(markers()).as(bad).isZero();
                jdbc.update("UPDATE public.plans SET archived_at = NULL, kind = 'PAID' WHERE code = 'TEAM'");
            }
        } finally {
            jdbc.update("UPDATE public.plans SET archived_at = NULL, kind = 'PAID' WHERE code = 'TEAM'");
            catalogStore.invalidate();
        }
    }

    @Test
    @DisplayName("migration job isolation: a PREMIUM tenant whose subscriptions table is broken fails alone — the other "
            + "is moved, the broken one keeps its public plan and gets no event, no done-marker is written")
    void brokenTenantFailsAlone() {
        String broken = register();
        UUID brokenId = tenants.get(tenants.size() - 1);
        plan("PREMIUM", "now() + interval '30 days'");
        plan(broken, brokenId, "PREMIUM", "now() + interval '30 days'");
        jdbc.execute("ALTER TABLE \"" + broken + "\".subscriptions RENAME TO subscriptions_broken");
        try {
            forgetMarker();
            migrationJob.migrateAll();

            assertThat(subscriptionPlan()).isEqualTo("TEAM");
            assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class,
                    brokenId)).isEqualTo("PREMIUM");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.tenant_plan_events WHERE tenant_id = ? "
                    + "AND cause = 'PLAN_MIGRATION'", Integer.class, brokenId)).isZero();
            assertThat(markers()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE \"" + broken + "\".subscriptions_broken RENAME TO subscriptions");
            jdbc.update("UPDATE \"" + broken + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", brokenId);
        }
    }

    @Test
    @DisplayName("migration job: a running PREMIUM_TRIAL is untouched and still expires to FREE")
    void premiumTrialRunsToTerm() throws Exception {
        plan("PREMIUM_TRIAL", "now() + interval '100 days'");
        forgetMarker();

        migrationJob.migrateAll();
        assertThat(subscriptionPlan()).isEqualTo("PREMIUM_TRIAL");
        assertThat(me().path("planType").asText()).isEqualTo("PREMIUM_TRIAL");
        assertThat(me().path("planLabel").asText()).isEqualTo("Essai Premium");

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 hour'");
        expiryJob.execute(new DowngradeExpiredTrialsCommand("test"));
        assertThat(subscriptionPlan()).isEqualTo("FREE");
    }

    // ── employee limits ───────────────────────────────────────────────────────

    @Test
    @DisplayName("grandfathering: a FREE tenant keeps its 3 active employees; a 4th (creation or reactivation) is "
            + "refused with the V1 details {employees, limit 1, current 3, plan FREE}")
    void freeGrandfathering() throws Exception {
        plan("FREE", "NULL");
        UUID storeB = newStore("Boutique B");
        employee(storeA, "ACTIVE");
        employee(storeA, "ACTIVE");
        employee(storeB, "ACTIVE");
        UUID inactive = employee(storeB, "INACTIVE");

        assertRefused(createEmployee(storeA), "\"entity\":\"employees\"", "\"limit\":1", "\"current\":3",
                "\"plan\":\"FREE\"");
        assertRefused(call(patch("/api/v1/employees/{id}/reactivate", inactive), ownerToken, null),
                "\"limit\":1", "\"current\":3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".employees WHERE status = 'ACTIVE'",
                Integer.class)).as("nobody deactivated").isEqualTo(3);
    }

    @Test
    @DisplayName("PRO per-store limit: a 4th employee in a full store is refused (scope store), the same employee "
            + "goes into another store; moving into the full store is refused too")
    void proPerStoreLimit() throws Exception {
        plan("PRO", "now() + interval '30 days'");
        UUID storeB = newStore("Boutique B");
        for (int i = 0; i < 3; i++) {
            employee(storeA, "ACTIVE");
        }
        UUID inB = employee(storeB, "ACTIVE");

        MvcResult full = createEmployee(storeA);
        assertRefused(full, "\"scope\":\"store\"", "\"limit\":3", "\"current\":3", "\"plan\":\"PREMIUM\"");
        MvcResult other = createEmployee(storeB);
        assertThat(other.getResponse().getStatus()).as(body(other)).isIn(200, 201);

        assertRefused(call(patch("/api/v1/employees/{id}/store", inB), ownerToken,
                Map.of("storeId", storeA.toString())), "\"scope\":\"store\"");
        UUID created = jdbc.queryForObject("SELECT e.id FROM \"" + schema + "\".employees e JOIN public.users u "
                + "ON u.id = e.user_id WHERE u.phone_number = ?", UUID.class, phones.get(phones.size() - 1));
        assertRefused(call(patch("/api/v1/employees/{id}", created), ownerToken,
                Map.of("storeId", storeA.toString())), "\"scope\":\"store\"");
        UUID inactiveInB = employee(storeB, "INACTIVE");
        jdbc.update("UPDATE \"" + schema + "\".employees SET store_id = ? WHERE id = ?", storeA, inactiveInB);
        assertRefused(call(patch("/api/v1/employees/{id}/reactivate", inactiveInB), ownerToken, null),
                "\"scope\":\"store\"");
    }

    // ── admin activation ──────────────────────────────────────────────────────

    private MvcResult activatePremium(String planCode) throws Exception {
        return activatePremium(planCode, 30);
    }

    private MvcResult activatePremium(String planCode, int days) throws Exception {
        Map<String, Object> payload = new HashMap<>(Map.of("expiresAt", LocalDate.now().plusDays(days).toString(),
                "amountXaf", 10_000, "method", "CASH"));
        if (planCode != null) {
            payload.put("planCode", planCode);
        }
        return call(post("/api/v1/admin/tenants/{id}/plan/premium", tenantId), adminToken, payload);
    }

    @Test
    @DisplayName("activate: no planCode → TEAM (plan_settings default); planCode PRO → PRO; FREE, BUSINESS_TRIAL, "
            + "PREMIUM (archived) and an unknown code → 422")
    void activatePaidPlan() throws Exception {
        MvcResult team = activatePremium(null);
        assertThat(team.getResponse().getStatus()).as(body(team)).isEqualTo(200);
        assertThat(body(team)).contains("\"planType\":\"TEAM\"");
        assertThat(subscriptionPlan()).isEqualTo("TEAM");

        MvcResult pro = activatePremium("pro", 60); // a running paid plan is renewed from its end: later expiry
        assertThat(pro.getResponse().getStatus()).as(body(pro)).isEqualTo(200);
        assertThat(subscriptionPlan()).isEqualTo("PRO");
        assertThat(row("invoicing")).isEqualTo("ACTIVE");
        assertThat(row("alerts")).isNotEqualTo("ACTIVE");

        for (String bad : List.of("FREE", "BUSINESS_TRIAL", "PREMIUM", "NOPE")) {
            MvcResult r = activatePremium(bad);
            assertThat(r.getResponse().getStatus()).as(bad + " " + body(r)).isEqualTo(422);
            assertThat(body(r)).contains("VALIDATION_ERROR").contains("Ce plan ne peut pas être attribué");
        }
        assertThat(subscriptionPlan()).isEqualTo("PRO");
    }

    private MvcResult activate(String planType, Instant expiresAt) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("planType", planType);
        payload.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
        return call(post("/api/v1/admin/subscriptions/{schema}/activate", schema), adminToken, payload);
    }

    @Test
    @DisplayName("legacy activate: expiresAt follows the plan kind (none for FREE, future for TRIAL/PAID); archived "
            + "plan refused — 422 with French messages")
    void activateExpiryByKind() throws Exception {
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);

        assertUnprocessable(activate("FREE", future), "Pas de date d'expiration pour un plan gratuit");
        assertUnprocessable(activate("TEAM", null), "Date d'expiration requise et future pour ce plan");
        assertUnprocessable(activate("TEAM", past), "Date d'expiration requise et future pour ce plan");
        assertUnprocessable(activate("BUSINESS_TRIAL", null), "Date d'expiration requise et future pour ce plan");
        assertUnprocessable(activate("PREMIUM", future), "Ce plan ne peut pas être attribué");
        assertThat(subscriptionPlan()).isEqualTo("BUSINESS_TRIAL");

        MvcResult team = activate("TEAM", future);
        assertThat(team.getResponse().getStatus()).as(body(team)).isEqualTo(200);
        assertThat(subscriptionPlan()).isEqualTo("TEAM");
        MvcResult free = activate("FREE", null);
        assertThat(free.getResponse().getStatus()).as(body(free)).isEqualTo(200);
        assertThat(subscriptionPlan()).isEqualTo("FREE");
    }

    private static void assertUnprocessable(MvcResult r, String message) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
        assertThat(body(r)).contains("VALIDATION_ERROR").contains(message);
    }

    // ── admin plan profiles ───────────────────────────────────────────────────

    @Test
    @DisplayName("admin plan profiles: label, kind, archived and maxEmployeesPerStore listed; PUT keeps the per-store "
            + "limit when absent and changes it when sent")
    void adminProfiles() throws Exception {
        MvcResult list = call(get("/api/v1/admin/plan-profiles"), adminToken, null);
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        Map<String, JsonNode> byPlan = new HashMap<>();
        objectMapper.readTree(body(list)).path("data").forEach(p -> byPlan.put(p.path("planType").asText(), p));
        assertThat(byPlan.get("PRO").path("label").asText()).isEqualTo("Pro");
        assertThat(byPlan.get("PRO").path("kind").asText()).isEqualTo("PAID");
        assertThat(byPlan.get("PRO").path("maxEmployeesPerStore").asInt()).isEqualTo(3);
        assertThat(byPlan.get("PREMIUM").path("archived").asBoolean()).isTrue();
        assertThat(byPlan.get("BUSINESS_TRIAL").path("kind").asText()).isEqualTo("TRIAL");
        assertThat(byPlan.get("BUSINESS").path("maxEmployeesPerStore").isNull()).isTrue();

        List<String> proModules = List.of("exports", "invoicing", "report-config");
        try {
            MvcResult kept = call(put("/api/v1/admin/plan-profiles/{p}", "PRO"), adminToken, Map.of(
                    "maxStores", 3, "maxProducts", 1000, "maxEmployees", 20, "modules", proModules));
            assertThat(kept.getResponse().getStatus()).as(body(kept)).isEqualTo(200);
            assertThat(body(kept)).contains("\"maxEmployeesPerStore\":3");
            Map<String, Object> withPerStore = new HashMap<>(Map.of("maxStores", 3, "maxEmployees", 20,
                    "maxEmployeesPerStore", 4, "modules", proModules));
            withPerStore.put("maxProducts", null);
            MvcResult changed = call(put("/api/v1/admin/plan-profiles/{p}", "PRO"), adminToken, withPerStore);
            assertThat(changed.getResponse().getStatus()).as(body(changed)).isEqualTo(200);
            assertThat(body(changed)).contains("\"maxEmployeesPerStore\":4");
            assertThat(jdbc.queryForObject("SELECT max_employees_per_store FROM public.plan_profiles "
                    + "WHERE plan_type = 'PRO'", Integer.class)).isEqualTo(4);
        } finally {
            jdbc.update("UPDATE public.plan_profiles SET max_stores = 3, max_products = NULL, max_employees = NULL, "
                    + "max_employees_per_store = 3 WHERE plan_type = 'PRO'");
        }
    }
}
