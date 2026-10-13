package com.keevo.admin.operations.adapter.in.rest;

import com.keevo.admin.operations.application.service.AdminOperationsService;
import com.keevo.admin.operations.domain.model.*;
import com.keevo.admin.operations.domain.port.in.OperationsPeriodQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminOperationsController")
class AdminOperationsControllerTest {

    private static final String TENANT_ID = UUID.randomUUID().toString();
    private static final Instant LAST_ACTIVITY = Instant.parse("2026-09-28T08:30:00Z");

    @Mock AdminOperationsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminOperationsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Spring Boot's ObjectMapper writes Instants as ISO strings (what the dashboard reads).
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── 403 for non-admin ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/admin/operations/overview?period=7D",
            "/api/v1/admin/operations/trend?period=7D",
            "/api/v1/admin/operations/tenants?period=7D&page=0&pageSize=25",
            "/api/v1/admin/operations/inactive-tenants"})
    @DisplayName("OWNER → 403 FORBIDDEN on every endpoint")
    void ownerIsForbidden(String url) throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    // ── overview ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /overview → 200 with every field read by the dashboard")
    void overview_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getOverview(new OperationsPeriodQuery("30D", null, null)))
                .thenReturn(new OperationsOverview(1500L, 2L, 1, 3, "30 derniers jours"));

        mockMvc.perform(get("/api/v1/admin/operations/overview").param("period", "30D"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/operations.ts (AdminOperationsOverview)
                .andExpect(jsonPath("$.data.totalGmv").value(1500))
                .andExpect(jsonPath("$.data.totalOrders").value(2))
                .andExpect(jsonPath("$.data.activeTenantsCount").value(1))
                .andExpect(jsonPath("$.data.newTenantsCount").value(3))
                .andExpect(jsonPath("$.data.periodLabel").value("30 derniers jours"));
    }

    @Test
    @DisplayName("GET /overview CUSTOM passes from/to; invalid range → 422 VALIDATION_ERROR")
    void overview_customInvalid_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getOverview(new OperationsPeriodQuery("CUSTOM", "2026-02-01", "2026-01-01")))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "from must be before or equal to to"));

        mockMvc.perform(get("/api/v1/admin/operations/overview")
                        .param("period", "CUSTOM").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    // ── trend ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /trend → 200 with ISO dates")
    void trend_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getTrend(new OperationsPeriodQuery("7D", null, null))).thenReturn(List.of(
                new GmvTrendPoint(LocalDate.of(2026, 10, 12), 0L, 0L),
                new GmvTrendPoint(LocalDate.of(2026, 10, 13), 1500L, 2L)));

        mockMvc.perform(get("/api/v1/admin/operations/trend").param("period", "7D"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/operations.ts (GmvTrendPoint)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].date").value("2026-10-13"))
                .andExpect(jsonPath("$.data[1].gmv").value(1500))
                .andExpect(jsonPath("$.data[1].orderCount").value(2))
                .andExpect(jsonPath("$.data[0].gmv").value(0));
    }

    // ── tenants ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /tenants → 200 page with every field read by the dashboard")
    void tenants_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        TenantActivity row = new TenantActivity(TENANT_ID, "Boutique Test", "PREMIUM_TRIAL",
                1500L, 2L, LAST_ACTIVITY, 3);
        when(service.listTenantActivity(eq(new OperationsPeriodQuery("90D", null, null)), eq(1), eq(25)))
                .thenReturn(new TenantActivityPage(List.of(row), 26L, 2, 1));

        mockMvc.perform(get("/api/v1/admin/operations/tenants")
                        .param("period", "90D").param("page", "1").param("pageSize", "25"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/operations.ts (AdminTenantActivityPage)
                .andExpect(jsonPath("$.data.totalElements").value(26))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.content[0].tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.data.content[0].tenantName").value("Boutique Test"))
                .andExpect(jsonPath("$.data.content[0].plan").value("PREMIUM_TRIAL"))
                .andExpect(jsonPath("$.data.content[0].gmv").value(1500))
                .andExpect(jsonPath("$.data.content[0].orderCount").value(2))
                .andExpect(jsonPath("$.data.content[0].lastActivityAt").value(LAST_ACTIVITY.toString()))
                .andExpect(jsonPath("$.data.content[0].storeCount").value(3));
    }

    @Test
    @DisplayName("GET /tenants without paging params delegates nulls (service defaults)")
    void tenants_defaults() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.listTenantActivity(any(), isNull(), isNull()))
                .thenReturn(new TenantActivityPage(List.of(), 0L, 0, 0));

        mockMvc.perform(get("/api/v1/admin/operations/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    // ── inactive-tenants ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /inactive-tenants → 200 with every field read by the dashboard")
    void inactive_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.listInactiveTenants()).thenReturn(List.of(
                new InactiveTenant(TENANT_ID, "Boutique Test", "FREE", LAST_ACTIVITY, 15L),
                new InactiveTenant("other", null, "PREMIUM", null, null)));

        mockMvc.perform(get("/api/v1/admin/operations/inactive-tenants"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/operations.ts (InactiveTenant)
                .andExpect(jsonPath("$.data[0].tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.data[0].tenantName").value("Boutique Test"))
                .andExpect(jsonPath("$.data[0].plan").value("FREE"))
                .andExpect(jsonPath("$.data[0].lastActivityAt").value(LAST_ACTIVITY.toString()))
                .andExpect(jsonPath("$.data[0].daysSinceLastActivity").value(15))
                .andExpect(jsonPath("$.data[1].lastActivityAt").doesNotExist())
                .andExpect(jsonPath("$.data[1].daysSinceLastActivity").doesNotExist());
    }
}
