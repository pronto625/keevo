package com.keevo.reporting.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 17.2 — the seller leaderboard shared with employees, on the real context (production {@link TenantFactory}
 * tenant on its registration trial, real JWTs, the employee's store resolved from its employee row): OFF by default
 * (employee 403), the owner turns it on ({@code PUT /tenant/leaderboard-visibility}, audited), the employee then sees
 * only its own store, anonymised; the owner's view is unchanged; a Free tenant gets {@code MODULE_NOT_ENTITLED}.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Leaderboard shared with employees — full context (Story 17.2)")
class LeaderboardEmployeeFullContextTest extends FullContextIntegrationTest {

    private static final String LEADERBOARD = "/api/v1/reporting/leaderboard?from=2026-10-12&to=2026-10-12";
    /** 12:00 WAT on 2026-10-12. */
    private static final Instant NOON = Instant.parse("2026-10-12T11:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantPreferencesRepository preferencesRepository;
    @Autowired ModuleRegistry moduleRegistry;

    private String schema;
    private UUID tenantId;
    private UUID storeA;
    private UUID storeB;
    private UUID me;
    private String ownerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, 'Boutique B', 'STORE', true, now(), now())", storeB);

        me = employee("Awa", "Ndiaye", storeA);
        UUID colleague = employee("Paul", "Ekane", storeA);
        UUID otherStoreSeller = employee("Bello", "Moussa", storeB);
        sale(me, storeA, 3_000);
        sale(colleague, storeA, 5_000);
        sale(colleague, storeA, 4_000);
        sale(otherStoreSeller, storeB, 50_000);

        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, UUID.randomUUID(), schema, "OWNER");
        employeeToken = TestAccess.token(jdbc, jwtTokenProvider, me, schema, "EMPLOYEE");
        TenantContext.setCurrentTenant(schema);
        try {
            preferencesRepository.save(new TenantPreferences(UUID.randomUUID(), SectorType.FOOD_GROCERY,
                    "20:00:00", true, Instant.now(), true, ReportChannel.WHATSAPP, true, 0, "20:00:00",
                    ReportChannel.WHATSAPP, true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH, true));
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        moduleRegistry.evict(schema);
        TenantContext.clear();
    }

    private UUID employee(String first, String last, UUID storeId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, ?, ?, false)",
                UUID.randomUUID(), userId, storeId, first, last);
        return userId;
    }

    private void sale(UUID sellerUserId, UUID storeId, int amount) {
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, created_at, employee_id, occurred_at, payment_mode, "
                        + "status, store_id, total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                UUID.randomUUID(), Timestamp.from(NOON), sellerUserId, Timestamp.from(NOON), storeId, amount);
    }

    private void freePlan() {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', max_stores = 1, "
                + "max_products = 500, max_employees = 2, expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
        moduleRegistry.evict(schema);
    }

    private MvcResult get(String token, String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private MvcResult putVisibility(String token, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/tenant/leaderboard-visibility")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static JsonNode json(ObjectMapper om, MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private boolean storedFlag() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT leaderboard_visible_to_employees FROM \"" + schema
                + "\".tenant_preferences", Boolean.class));
    }

    private int auditRows(String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".audit_log WHERE action = ?",
                Integer.class, action);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("default off: GET flag false, employee 403 FORBIDDEN in French, owner view unchanged (me false)")
    void offByDefault() throws Exception {
        MvcResult prefs = get(ownerToken, "/api/v1/tenant/preferences");
        assertThat(json(objectMapper, prefs).path("data").path("leaderboardVisibleToEmployees").asBoolean(true))
                .isFalse();

        MvcResult employee = get(employeeToken, LEADERBOARD);
        JsonNode body = json(objectMapper, employee);
        assertThat(employee.getResponse().getStatus()).as(body.toString()).isEqualTo(403);
        assertThat(body.path("domainCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("error").asText()).isEqualTo("Le classement n'est pas partagé avec les employés.");

        MvcResult owner = get(ownerToken, LEADERBOARD);
        JsonNode rows = json(objectMapper, owner).path("data");
        assertThat(owner.getResponse().getStatus()).isEqualTo(200);
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).path("sellerName").asText()).isEqualTo("Bello Moussa");
        rows.forEach(r -> assertThat(r.path("me").asBoolean(true)).isFalse());
        rows.forEach(r -> assertThat(r.path("sellerId").isNull()).isFalse());
    }

    @Test
    @DisplayName("owner turns it on: audited; employee sees only store A, own row named + me, other « Vendeur N »")
    void sharedWithEmployees() throws Exception {
        MvcResult put = putVisibility(ownerToken, "{\"visibleToEmployees\": true}");
        assertThat(put.getResponse().getStatus()).as(put.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(objectMapper, put).path("data").path("leaderboardVisibleToEmployees").asBoolean()).isTrue();
        assertThat(storedFlag()).isTrue();
        assertThat(auditRows("LEADERBOARD_SHARED_WITH_EMPLOYEES")).isEqualTo(1);

        MvcResult r = get(employeeToken, LEADERBOARD + "&storeId=" + storeB);
        JsonNode body = json(objectMapper, r);
        assertThat(r.getResponse().getStatus()).as(body.toString()).isEqualTo(200);
        JsonNode rows = body.path("data");
        List<String> names = new ArrayList<>();
        rows.forEach(x -> names.add(x.path("sellerName").asText()));
        assertThat(names).containsExactly("Vendeur 1", "Awa Ndiaye"); // store B forced away, colleague anonymised

        JsonNode colleague = rows.get(0);
        assertThat(colleague.path("rank").asInt()).isEqualTo(1);
        assertThat(colleague.path("sellerId").isNull()).isTrue();
        assertThat(colleague.path("totalRevenue").asLong()).isEqualTo(9_000);
        assertThat(colleague.path("salesCount").asInt()).isEqualTo(2);
        assertThat(colleague.path("me").asBoolean(true)).isFalse();
        JsonNode mine = rows.get(1);
        assertThat(mine.path("sellerId").asText()).isEqualTo(me.toString());
        assertThat(mine.path("me").asBoolean()).isTrue();
        assertThat(mine.path("totalRevenue").asLong()).isEqualTo(3_000);

        // Hidden again → audited, employee refused.
        assertThat(putVisibility(ownerToken, "{\"visibleToEmployees\": false}").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(auditRows("LEADERBOARD_HIDDEN_FROM_EMPLOYEES")).isEqualTo(1);
        assertThat(get(employeeToken, LEADERBOARD).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("employee cannot toggle (403 FORBIDDEN), missing field → 422, nothing written")
    void employeeCannotToggle() throws Exception {
        MvcResult r = putVisibility(employeeToken, "{\"visibleToEmployees\": true}");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(objectMapper, r).path("domainCode").asText()).isEqualTo("FORBIDDEN");

        MvcResult empty = putVisibility(ownerToken, "{}");
        assertThat(empty.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(objectMapper, empty).path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(storedFlag()).isFalse();
    }

    @Test
    @DisplayName("Free tenant: PUT and employee GET → 403 MODULE_NOT_ENTITLED, nothing written")
    void freeTenant() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".tenant_preferences SET leaderboard_visible_to_employees = true");
        freePlan();

        MvcResult put = putVisibility(ownerToken, "{\"visibleToEmployees\": false}");
        assertThat(put.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(objectMapper, put).path("domainCode").asText()).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(storedFlag()).isTrue();

        MvcResult employee = get(employeeToken, LEADERBOARD);
        assertThat(employee.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(objectMapper, employee).path("domainCode").asText()).isEqualTo("MODULE_NOT_ENTITLED");

        // report-config DISABLED on Free: the leaderboard flag keeps its stored value (17.8 rule untouched).
        assertThat(json(objectMapper, get(ownerToken, "/api/v1/tenant/preferences")).path("data")
                .path("leaderboardVisibleToEmployees").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("trial ended < 7 days (advanced-reports READ_ONLY): owner GET 200, employee 403 even with the flag on")
    void graceRefusesEmployees() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".tenant_preferences SET leaderboard_visible_to_employees = true");
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 minute'");
        moduleRegistry.evict(schema);

        assertThat(get(ownerToken, LEADERBOARD).getResponse().getStatus()).isEqualTo(200);
        MvcResult employee = get(employeeToken, LEADERBOARD);
        JsonNode body = json(objectMapper, employee);
        assertThat(employee.getResponse().getStatus()).as(body.toString()).isEqualTo(403);
        assertThat(body.path("domainCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("error").asText()).isEqualTo("Le classement n'est pas partagé avec les employés.");
    }
}
