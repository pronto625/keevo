package com.keevo.shared.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Story 14.5 (MOD-FR10, UX-DR3) — {@code GET /api/v1/tenants/me/extensions/{pointId}} on the shipped catalogue:
 * with pos and invoicing active, the POS checkout slot lists « Facturer » for the owner and the employee (both hold
 * {@code INVOICE_VIEW}); invoicing DISABLED / FREE / READ_ONLY or pos DISABLED → empty; unknown point → 404.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Tenant extensions endpoint — full context (Story 14.5)")
class TenantExtensionsFullContextTest extends FullContextIntegrationTest {

    private static final String SLOT_URL = "/api/v1/tenants/me/extensions/pos.checkout.post-sale-actions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;

    private String schema;
    private UUID tenantId;
    private String ownerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID employeeId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeId);
        ownerToken = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), schema, "OWNER");
        employeeToken = jwtTokenProvider.generateAccessToken(employeeId, schema, "EMPLOYEE", "ACTIVE", storeId,
                false);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
    }

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE') "
                + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status", tenantId, moduleId,
                status);
        registry.evict(schema);
    }

    private void plan(String planType, String expiresInterval) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = '" + planType + "', expires_at = "
                + (expiresInterval == null ? "NULL" : "now() + interval '" + expiresInterval + "'"));
        registry.evict(schema);
    }

    private MvcResult call(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
    }

    private JsonNode slot(String token) throws Exception {
        MvcResult r = call(token, SLOT_URL);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        return objectMapper.readTree(body).path("data");
    }

    private static void assertFacturer(JsonNode data) {
        assertThat(data.size()).isEqualTo(1);
        JsonNode e = data.get(0);
        assertThat(e.path("id").asText()).isEqualTo("invoicing.invoice-sale");
        assertThat(e.path("label").asText()).isEqualTo("Facturer");
        assertThat(e.path("order").asInt()).isEqualTo(100);
        assertThat(e.path("action").asText()).isEqualTo("invoicing.open-sale-invoice");
        assertThat(e.has("permission")).isFalse();
        assertThat(e.has("module")).isFalse();
    }

    @Test
    @DisplayName("pos and invoicing active: owner and employee get « Facturer »")
    void ownerAndEmployee() throws Exception {
        assertThat(registry.moduleState(schema, "pos")).isEqualTo(ModuleState.ENABLED);
        assertThat(registry.moduleState(schema, "invoicing")).isEqualTo(ModuleState.ENABLED);
        assertFacturer(slot(ownerToken));
        assertFacturer(slot(employeeToken));
    }

    @Test
    @DisplayName("invoicing DISABLED → empty; the invoices endpoints answer 403 as before")
    void invoicingDisabled() throws Exception {
        moduleRow("invoicing", "DISABLED");
        assertThat(slot(ownerToken).size()).isZero();
        MvcResult r = call(ownerToken, "/api/v1/invoices");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("FREE tenant → empty")
    void freeTenant() throws Exception {
        plan("FREE", null);
        assertThat(slot(ownerToken).size()).isZero();
        assertThat(slot(employeeToken).size()).isZero();
    }

    @Test
    @DisplayName("invoicing READ_ONLY (grace) → empty")
    void invoicingReadOnly() throws Exception {
        plan("PREMIUM_TRIAL", "-1 day");
        assertThat(registry.moduleState(schema, "invoicing")).isEqualTo(ModuleState.READ_ONLY);
        assertThat(slot(ownerToken).size()).isZero();
    }

    @Test
    @DisplayName("pos DISABLED (declaring module off) → empty")
    void posDisabled() throws Exception {
        moduleRow("pos", "DISABLED");
        assertThat(slot(ownerToken).size()).isZero();
    }

    @Test
    @DisplayName("unknown or malformed point → 404 « Point d'extension inconnu », nothing echoed")
    void unknownPoint() throws Exception {
        for (String point : new String[]{"foo.bar", "POS", "pos..checkout", "pos.checkout.post-sale-actions-DROP"}) {
            MvcResult r = call(ownerToken, "/api/v1/tenants/me/extensions/" + point);
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(r.getResponse().getStatus()).as(body).isEqualTo(404);
            assertThat(body).contains("Point d'extension inconnu").contains("NOT_FOUND")
                    .doesNotContain("foo.bar").doesNotContain("DROP");
        }
    }

    @Test
    @DisplayName("no token → 401")
    void anonymous() throws Exception {
        assertThat(mockMvc.perform(get(SLOT_URL)).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
