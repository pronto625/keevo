package com.keevo.admin.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.application.port.PlanCatalogStore;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Story 19.7a — plans are data, end to end in the real application context: a fourth plan inserted as data (no code
 * change) behaves like the seeded plan of its kind everywhere — limits from its own profile, paid modules from its
 * {@code profile_modules}, {@code GET /subscription/me} and {@code PLAN_LIMIT_EXCEEDED} answering the V1 code of its
 * kind, the admin list filters and the revenue counts, the nightly expiry downgrading it to the free plan of
 * {@code plan_settings}. A trial-kind plan behaves like {@code PREMIUM_TRIAL}; the registration trial length is
 * read from {@code plan_settings}. Every test restores the seed (plans are never deleted in production: the test
 * codes only exist here).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Plans are data — a plan created as data works everywhere (Story 19.7a)")
class PlansAreDataFullContextTest extends FullContextIntegrationTest {

    private static final PlanType TEST_PAID = PlanType.of("TEST_PAID");
    private static final PlanType TEST_TRIAL = PlanType.of("TEST_TRIAL");
    private static final PlanType TEST_FREE = PlanType.of("TEST_FREE");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanCatalog catalog;
    @Autowired PlanCatalogStore catalogStore;
    @Autowired PlanModuleRowSync rowSync;
    @Autowired DowngradeExpiredTrialsUseCase expiryJob;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<UUID> tenants = new ArrayList<>();
    private String schema;
    private UUID tenantId;
    private String tenantCode;
    private String adminToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        schema = register();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        tenantCode = jdbc.queryForObject("SELECT code FROM public.tenants WHERE id = ?", String.class, tenantId);
        adminToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, adminId,
                "public", "SUPER_ADMIN");
        ownerToken = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId,
                schema, "OWNER");
        catalogStore.save(new PlanDefinition(TEST_PAID, "Test payant", null, 90, false, PlanKind.PAID, null));
        catalogStore.save(new PlanDefinition(TEST_TRIAL, "Test essai", null, 91, false, PlanKind.TRIAL, null));
        catalogStore.save(new PlanDefinition(TEST_FREE, "Test gratuit", null, 92, false, PlanKind.FREE, null));
        // its own profile, as data: 1 store, unlimited products, 5 employees; exports granted, invoicing withheld,
        // every other paid module undecided (the PAID kind grants it)
        for (PlanType plan : List.of(TEST_PAID, TEST_TRIAL)) {
            jdbc.update("INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, max_employees) "
                    + "VALUES (?, 1, NULL, 5)", plan.code());
            jdbc.update("INSERT INTO public.profile_modules (plan_type, module_id, granted) VALUES (?, 'exports', true),"
                    + " (?, 'invoicing', false)", plan.code(), plan.code());
        }
    }

    @AfterEach
    void restoreSeed() {
        for (UUID tenant : tenants) { // back on the free plan, test events and module rows removed
            String s = jdbc.queryForObject("SELECT schema_name FROM public.tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE \"" + s + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
            jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenant);
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenant);
        }
        jdbc.update("DELETE FROM public.tenant_plan_events WHERE from_plan LIKE 'TEST\\_%' OR to_plan LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.profile_modules WHERE plan_type LIKE 'TEST\\_%'");
        jdbc.update("DELETE FROM public.plan_profiles WHERE plan_type LIKE 'TEST\\_%'");
        catalogStore.saveSettings(new PlanSettings(PlanType.FREE, PlanType.PREMIUM_TRIAL, 6));
        jdbc.update("DELETE FROM public.plans WHERE code LIKE 'TEST\\_%'");
        catalogStore.invalidate();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String register() {
        String s = tx.execute(st -> tenantFactory.create()).getSchemaName();
        tenants.add(jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, s));
        return s;
    }

    /** Moves the test tenant onto a plan the way the plan writers do (schema row, public row, module rows). */
    private void plan(PlanType plan, String expiresSql) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = ?, status = 'ACTIVE', expires_at = "
                + expiresSql, plan.code());
        jdbc.update("UPDATE public.tenants SET plan_type = ? WHERE id = ?", plan.code(), tenantId);
        rowSync.sync(tenantId, schema);
    }

    private JsonNode me() throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/subscription/me")
                .header("Authorization", "Bearer " + ownerToken)).andReturn();
        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);
        return objectMapper.readTree(body(me)).path("data");
    }

    private String row(String moduleId) {
        List<String> rows = jdbc.queryForList("SELECT status || '/' || tier || '/' || origin FROM public.tenant_modules "
                + "WHERE tenant_id = ? AND module_id = ?", String.class, tenantId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Ids of the tenants the admin list shows for this plan filter, searched by the test tenant's code. */
    private List<String> listed(String planFilter) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/admin/tenants").param("plan", planFilter)
                .param("search", tenantCode).header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        objectMapper.readTree(body(list)).path("data").path("items").forEach(i -> codes.add(i.path("id").asText()));
        return codes;
    }

    private JsonNode revenue() throws Exception {
        MvcResult summary = mockMvc.perform(get("/api/v1/admin/revenue/summary")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(summary.getResponse().getStatus()).as(body(summary)).isEqualTo(200);
        return objectMapper.readTree(body(summary)).path("data");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 4th plan of kind PAID, as data: own limits, own paid modules, V1 « PREMIUM », admin filters and "
            + "revenue count it as paid, the expiry job downgrades it to the free plan")
    void paidPlanAsData() throws Exception {
        JsonNode before = revenue();
        plan(TEST_PAID, "now() + interval '30 days'");

        // V1 surface: the plan's V1 code, its own limits
        JsonNode me = me();
        assertThat(me.path("planType").asText()).isEqualTo("PREMIUM");
        assertThat(me.path("maxStores").asInt()).isEqualTo(1);
        assertThat(me.path("maxProducts").isNull()).isTrue();
        assertThat(me.path("maxEmployees").asInt()).isEqualTo(5);

        // limits from its profile; the refusal names the V1 code
        MvcResult store = mockMvc.perform(post("/api/v1/stores").header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Boutique refusée")))).andReturn();
        assertThat(store.getResponse().getStatus()).as(body(store)).isEqualTo(403);
        assertThat(body(store)).contains("PLAN_LIMIT_EXCEEDED").contains("\"plan\":\"PREMIUM\"")
                .contains("\"limit\":1").doesNotContain("TEST_PAID");

        // paid modules from its profile_modules, undecided ones by its kind
        assertThat(row("exports")).isEqualTo("ACTIVE/PREMIUM/PLAN");
        assertThat(row("alerts")).isEqualTo("ACTIVE/PREMIUM/PLAN");
        assertThat(row("invoicing")).startsWith("DISABLED/");

        // admin: paid in the legacy PAID filter, its own code filters, not free; revenue counts it as paid
        assertThat(listed("PAID")).containsExactly(tenantId.toString());
        assertThat(listed("test_paid")).containsExactly(tenantId.toString());
        assertThat(listed("FREE")).isEmpty();
        MvcResult unknown = mockMvc.perform(get("/api/v1/admin/tenants").param("plan", "GOLD")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(unknown)).contains("VALIDATION_ERROR");
        JsonNode after = revenue();
        assertThat(after.path("premiumTenants").asInt()).isEqualTo(before.path("premiumTenants").asInt() + 1);
        assertThat(after.path("trialTenants").asInt()).isEqualTo(before.path("trialTenants").asInt() - 1);

        // expiry: downgraded to plan_settings.free_plan_code, history TEST_PAID → FREE
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 hour'");
        expiryJob.execute(new DowngradeExpiredTrialsCommand("test"));
        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"" + schema + "\".subscriptions", String.class))
                .isEqualTo("FREE");
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("FREE");
        assertThat(jdbc.queryForList("SELECT from_plan || '>' || to_plan || ':' || cause FROM public.tenant_plan_events "
                + "WHERE tenant_id = ? ORDER BY occurred_at DESC LIMIT 1", String.class, tenantId))
                .containsExactly("TEST_PAID>FREE:EXPIRY");
        assertThat(me().path("planType").asText()).isEqualTo("FREE");
        assertThat(row("exports")).startsWith("EXPIRED/"); // read-only grace, like an expired Premium
    }

    @Test
    @DisplayName("a plan of kind TRIAL behaves like PREMIUM_TRIAL: V1 « PREMIUM_TRIAL », TRIAL module rows, expiry")
    void trialPlanAsData() throws Exception {
        plan(TEST_TRIAL, "now() + interval '10 days'");

        assertThat(me().path("planType").asText()).isEqualTo("PREMIUM_TRIAL");
        assertThat(row("exports")).isEqualTo("TRIAL/PREMIUM/PLAN");
        assertThat(row("invoicing")).startsWith("DISABLED/");
        assertThat(listed("PAID")).containsExactly(tenantId.toString());

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 hour'");
        expiryJob.execute(new DowngradeExpiredTrialsCommand("test"));
        assertThat(me().path("planType").asText()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("plan_settings free plan = a data plan of kind FREE: the expiry job and the admin downgrade move the "
            + "tenant onto it (schema, public row, history)")
    void freePlanFromSettings() throws Exception {
        catalogStore.saveSettings(new PlanSettings(TEST_FREE, PlanType.PREMIUM_TRIAL, 6));

        plan(TEST_PAID, "now() - interval '1 hour'");
        expiryJob.execute(new DowngradeExpiredTrialsCommand("test"));
        assertOnTestFree("EXPIRY");

        plan(TEST_PAID, "now() + interval '30 days'");
        MvcResult down = mockMvc.perform(post("/api/v1/admin/tenants/{id}/plan/free", tenantId)
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(down.getResponse().getStatus()).as(body(down)).isEqualTo(200);
        assertThat(body(down)).contains("\"planType\":\"TEST_FREE\"");
        assertOnTestFree("ADMIN_DOWNGRADE");
    }

    private void assertOnTestFree(String cause) {
        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"" + schema + "\".subscriptions", String.class))
                .isEqualTo("TEST_FREE");
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE id = ?", String.class, tenantId))
                .isEqualTo("TEST_FREE");
        assertThat(jdbc.queryForList("SELECT to_plan || ':' || cause FROM public.tenant_plan_events "
                + "WHERE tenant_id = ? ORDER BY occurred_at DESC LIMIT 1", String.class, tenantId))
                .containsExactly("TEST_FREE:" + cause);
    }

    @Test
    @DisplayName("registration: trial plan and length from plan_settings (6 months seeded; 1 month after a change)")
    void registrationTrialFromSettings() {
        String sixMonths = jdbc.queryForObject("SELECT plan_type || ':' || (expires_at - created_at > interval '180 days' "
                + "AND expires_at - created_at < interval '185 days') FROM \"" + schema + "\".subscriptions", String.class);
        assertThat(sixMonths).isEqualTo("PREMIUM_TRIAL:true");

        catalogStore.saveSettings(new PlanSettings(PlanType.FREE, TEST_TRIAL, 1));
        String next = register();
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM \"" + next + "\".subscriptions",
                java.sql.Timestamp.class).toInstant();
        assertThat(Duration.between(Instant.now(), expires).toDays()).isBetween(27L, 31L);
        assertThat(jdbc.queryForObject("SELECT plan_type FROM \"" + next + "\".subscriptions", String.class))
                .isEqualTo("TEST_TRIAL");
        assertThat(jdbc.queryForObject("SELECT plan_type FROM public.tenants WHERE schema_name = ?", String.class, next))
                .isEqualTo("TEST_TRIAL");
        assertThat(jdbc.queryForObject("SELECT to_plan FROM public.tenant_plan_events e JOIN public.tenants t "
                + "ON t.id = e.tenant_id WHERE t.schema_name = ? AND e.cause = 'REGISTRATION'", String.class, next))
                .isEqualTo("TEST_TRIAL");
    }

    @Test
    @DisplayName("admin plan profiles: every catalogue plan listed with its catalogue label; unknown code → 404")
    void adminProfilesFollowTheCatalogue() throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/admin/plan-profiles")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(body(list)).contains("\"planType\":\"TEST_PAID\"").contains("\"label\":\"Test payant\"")
                .contains("\"label\":\"Gratuit\"");
        MvcResult one = mockMvc.perform(get("/api/v1/admin/plan-profiles/{p}", "test_paid")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(one.getResponse().getStatus()).as(body(one)).isEqualTo(200);
        assertThat(body(one)).contains("\"maxEmployees\":5");
        MvcResult nope = mockMvc.perform(get("/api/v1/admin/plan-profiles/{p}", "NOPE")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
        assertThat(nope.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(nope)).contains("PLAN_PROFILE_NOT_FOUND");
        assertThat(catalog.kind(TEST_PAID)).isEqualTo(PlanKind.PAID);
    }
}
