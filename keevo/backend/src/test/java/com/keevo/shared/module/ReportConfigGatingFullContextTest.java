package com.keevo.shared.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 17.8 — report configuration gating on the real context (production {@link TenantFactory} tenant on its
 * registration trial, plan changed in its own {@code subscriptions} row, same style as
 * {@link PremiumGatingFullContextTest}): with {@code report-config} DISABLED every consumer of
 * {@link TenantPreferencesQueryPort} and {@code GET /tenant/preferences} see the fixed defaults; the stored trial
 * configuration is kept and comes back on Premium.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Report configuration gating — full context (Story 17.8)")
class ReportConfigGatingFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantPreferencesRepository preferencesRepository;
    @Autowired TenantPreferencesQueryPort preferencesQuery;
    @Autowired ModuleRegistry moduleRegistry;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private String token;

    /**
     * What the tenant configured during its trial: EOD off at 23:00 in-app only, weekly Wednesday 07:00, … (as the
     * repository reads it back: {@code LocalTime#toString}, so {@code "23:00"}).
     */
    private static final TenantPreferencesView TRIAL_PREFS = new TenantPreferencesView("23:00", false, false,
            ReportChannel.IN_APP_ONLY, false, 3, "07:00", ReportChannel.IN_APP_ONLY, false,
            ReportChannel.IN_APP_ONLY, StockAlertChannel.BOTH, false);

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        token = com.keevo.shared.infrastructure.security.TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        TenantContext.setCurrentTenant(schema);
        try {
            preferencesRepository.save(new TenantPreferences(UUID.randomUUID(), SectorType.FOOD_GROCERY,
                    "23:00:00", false, Instant.now(), false, ReportChannel.IN_APP_ONLY, false, 3, "07:00:00",
                    ReportChannel.IN_APP_ONLY, false, ReportChannel.IN_APP_ONLY, StockAlertChannel.BOTH, false));
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
    }

    private void freePlan() {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', max_stores = 1, "
                + "max_products = 500, max_employees = 2, expires_at = NULL");
        jdbc.update("UPDATE public.tenants SET plan_type = 'FREE' WHERE id = ?", tenantId);
    }

    private TenantPreferencesView portView() {
        TenantContext.setCurrentTenant(schema);
        try {
            return preferencesQuery.find(schema).orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    private JsonNode getPreferences() throws Exception {
        MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/tenant/preferences")
                .header("Authorization", "Bearer " + token)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        return objectMapper.readTree(body).path("data");
    }

    private String storedEodTime() {
        return jdbc.queryForObject("SELECT eod_report_time::text FROM \"" + schema + "\".tenant_preferences",
                String.class);
    }

    @Test
    @DisplayName("Free tenant with trial preferences: port and GET give the fixed defaults, flag false, row untouched")
    void free_fixedDefaults() throws Exception {
        freePlan();

        assertThat(portView()).isEqualTo(TenantPreferencesView.fixedDefaults());
        JsonNode data = getPreferences();
        assertThat(data.path("eodReportEnabled").asBoolean()).isTrue();
        assertThat(data.path("eodReportTime").asText()).isEqualTo("20:00");
        assertThat(data.path("eodReportChannel").asText()).isEqualTo("WHATSAPP");
        assertThat(data.path("weeklyReportDay").asInt()).isZero();
        assertThat(data.path("stockAlertChannel").asText()).isEqualTo("PUSH");
        assertThat(data.path("sectorType").asText()).isEqualTo("FOOD_GROCERY");
        assertThat(data.path("reportConfigEditable").asBoolean(true)).isFalse();
        assertThat(storedEodTime()).isEqualTo("23:00:00");
    }

    @Test
    @DisplayName("17.2 Free tenant: the leaderboard flag keeps its stored value (not a report-config field)")
    void free_leaderboardFlagKept() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".tenant_preferences SET leaderboard_visible_to_employees = true");
        freePlan();

        assertThat(portView()).isEqualTo(TenantPreferencesView.fixedDefaults().withLeaderboardVisibleToEmployees(true));
        assertThat(getPreferences().path("leaderboardVisibleToEmployees").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Premium trial: stored values, flag true")
    void trial_storedValues() throws Exception {
        assertThat(portView()).isEqualTo(TRIAL_PREFS);
        JsonNode data = getPreferences();
        assertThat(data.path("eodReportEnabled").asBoolean(true)).isFalse();
        assertThat(data.path("eodReportTime").asText()).isEqualTo("23:00");
        assertThat(data.path("eodReportChannel").asText()).isEqualTo("IN_APP_ONLY");
        assertThat(data.path("reportConfigEditable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Trial ended 1 min ago (READ_ONLY grace): stored values kept, flag false")
    void grace_storedValuesNotEditable() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '1 minute'");

        assertThat(portView()).isEqualTo(TRIAL_PREFS);
        JsonNode data = getPreferences();
        assertThat(data.path("eodReportTime").asText()).isEqualTo("23:00");
        assertThat(data.path("reportConfigEditable").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("Trial ended 8 days ago (grace over → DISABLED): fixed defaults, flag false")
    void graceOver_fixedDefaults() throws Exception {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET expires_at = now() - interval '8 days'");

        assertThat(portView()).isEqualTo(TenantPreferencesView.fixedDefaults());
        JsonNode data = getPreferences();
        assertThat(data.path("eodReportTime").asText()).isEqualTo("20:00");
        assertThat(data.path("eodReportChannel").asText()).isEqualTo("WHATSAPP");
        assertThat(data.path("reportConfigEditable").asBoolean(true)).isFalse();
        assertThat(storedEodTime()).isEqualTo("23:00:00");
    }

    @Test
    @DisplayName("Free tenant with a report-config row ACTIVE (à la carte): stored values, editable")
    void free_rowActive_storedValues() throws Exception {
        freePlan();
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, ?)",
                tenantId, "report-config", "ACTIVE", "PREMIUM");

        assertThat(portView()).isEqualTo(TRIAL_PREFS);
        assertThat(getPreferences().path("reportConfigEditable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Back to Premium after Free: the stored trial configuration comes back")
    void freeThenPremium_storedValuesBack() {
        freePlan();
        assertThat(portView()).isEqualTo(TenantPreferencesView.fixedDefaults());

        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PREMIUM', "
                + "expires_at = now() + interval '30 days'");
        jdbc.update("UPDATE public.tenants SET plan_type = 'PREMIUM' WHERE id = ?", tenantId);
        moduleRegistry.evict(schema); // the test thread is a (mock) request: drop its cached module rows / plan
        assertThat(portView()).isEqualTo(TRIAL_PREFS);
    }
}
