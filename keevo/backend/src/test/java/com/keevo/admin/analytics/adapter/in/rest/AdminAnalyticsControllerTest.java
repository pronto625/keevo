package com.keevo.admin.analytics.adapter.in.rest;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.keevo.admin.analytics.application.service.AdminAnalyticsService;
import com.keevo.admin.analytics.domain.model.*;
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAnalyticsController (9-4)")
class AdminAnalyticsControllerTest {

    @Mock AdminAnalyticsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAnalyticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Spring Boot's ObjectMapper writes dates as ISO strings (what the dashboard reads).
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/admin/analytics/overview",
            "/api/v1/admin/analytics/growth?months=12",
            "/api/v1/admin/analytics/geography",
            "/api/v1/admin/analytics/retention?weeks=8",
            "/api/v1/admin/analytics/top-stores",
            "/api/v1/admin/analytics/adoption"})
    @DisplayName("OWNER → 403 FORBIDDEN on every endpoint")
    void ownerIsForbidden(String url) throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /overview → every field read by the dashboard")
    void overview_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getOverview()).thenReturn(new AnalyticsOverview(3, 10, 0.3, 12, 15, 9, 4200, 870, 1_250_000));

        mockMvc.perform(get("/api/v1/admin/analytics/overview"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/analytics.ts (AnalyticsOverview)
                .andExpect(jsonPath("$.data.dau").value(3))
                .andExpect(jsonPath("$.data.mau").value(10))
                .andExpect(jsonPath("$.data.dauMauRatio").value(0.3))
                .andExpect(jsonPath("$.data.totalTenants").value(12))
                .andExpect(jsonPath("$.data.totalStores").value(15))
                .andExpect(jsonPath("$.data.activeStores").value(9))
                .andExpect(jsonPath("$.data.totalProducts").value(4200))
                .andExpect(jsonPath("$.data.totalCompletedSales").value(870))
                .andExpect(jsonPath("$.data.totalGmv").value(1_250_000));
    }

    @Test
    @DisplayName("GET /overview with MAU 0 → dauMauRatio null")
    void overview_nullRatio() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getOverview()).thenReturn(new AnalyticsOverview(0, 0, null, 0, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/admin/analytics/overview"))
                .andExpect(status().isOk())
                // Key present with a null value (a missing key would also pass a doesNotExist check).
                .andExpect(jsonPath("$.data", hasKey("dauMauRatio")))
                .andExpect(jsonPath("$.data.dauMauRatio").value(nullValue()));
    }

    @Test
    @DisplayName("GET /growth → months passed through, points with every field")
    void growth_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getGrowth("6")).thenReturn(List.of(new GrowthPoint("2026-10", 2, 12, 40, 90_000, 25)));

        mockMvc.perform(get("/api/v1/admin/analytics/growth").param("months", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].month").value("2026-10"))
                .andExpect(jsonPath("$.data[0].newTenants").value(2))
                .andExpect(jsonPath("$.data[0].cumulativeTenants").value(12))
                .andExpect(jsonPath("$.data[0].salesCount").value(40))
                .andExpect(jsonPath("$.data[0].gmv").value(90_000))
                .andExpect(jsonPath("$.data[0].mau").value(25));
    }

    @Test
    @DisplayName("GET /growth?months=abc → 422 VALIDATION_ERROR (validated by the service)")
    void growth_invalid_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getGrowth("abc")).thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "months"));

        mockMvc.perform(get("/api/v1/admin/analytics/growth").param("months", "abc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /geography → country codes and counts")
    void geography_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getGeography()).thenReturn(List.of(new CountryCount("CM", 7), new CountryCount("UNKNOWN", 1)));

        mockMvc.perform(get("/api/v1/admin/analytics/geography"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].country").value("CM"))
                .andExpect(jsonPath("$.data[0].tenantCount").value(7))
                .andExpect(jsonPath("$.data[1].country").value("UNKNOWN"));
    }

    @Test
    @DisplayName("GET /retention → ISO week starts, null future cells kept in the array")
    void retention_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getRetention(null)).thenReturn(new RetentionReport(3, List.of(
                new RetentionCohort(LocalDate.of(2026, 9, 28), 4, Arrays.asList(0.75, 0.25, null)))));

        mockMvc.perform(get("/api/v1/admin/analytics/retention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeks").value(3))
                .andExpect(jsonPath("$.data.cohorts[0].weekStart").value("2026-09-28"))
                .andExpect(jsonPath("$.data.cohorts[0].tenantCount").value(4))
                .andExpect(jsonPath("$.data.cohorts[0].retention.length()").value(3))
                .andExpect(jsonPath("$.data.cohorts[0].retention[0]").value(0.75))
                .andExpect(jsonPath("$.data.cohorts[0].retention[2]").value(nullValue()));
    }

    @Test
    @DisplayName("GET /top-stores → range and stores, no id; growth null kept as null")
    void topStores_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getTopStores("2026-10-01", "2026-10-13")).thenReturn(new TopStoresReport(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), List.of(
                        new TopStore("Akwa", "Boutique A", 30_000, 4, 7_500, 50.0),
                        new TopStore("Bonapriso", "Sans nom", 20_000, 2, 10_000, null))));

        mockMvc.perform(get("/api/v1/admin/analytics/top-stores")
                        .param("from", "2026-10-01").param("to", "2026-10-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.to").value("2026-10-13"))
                .andExpect(jsonPath("$.data.stores[0].storeName").value("Akwa"))
                .andExpect(jsonPath("$.data.stores[0].tenantName").value("Boutique A"))
                .andExpect(jsonPath("$.data.stores[0].gmv").value(30_000))
                .andExpect(jsonPath("$.data.stores[0].transactions").value(4))
                .andExpect(jsonPath("$.data.stores[0].averageBasket").value(7_500))
                .andExpect(jsonPath("$.data.stores[0].growthPercent").value(50.0))
                .andExpect(jsonPath("$.data.stores[0].storeId").doesNotExist())
                .andExpect(jsonPath("$.data.stores[1]", hasKey("growthPercent")))
                .andExpect(jsonPath("$.data.stores[1].growthPercent").value(nullValue()));
    }

    @Test
    @DisplayName("GET /top-stores with from > to → 422 VALIDATION_ERROR")
    void topStores_badRange_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getTopStores("2026-02-01", "2026-01-01"))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "from must be before or equal to to"));

        mockMvc.perform(get("/api/v1/admin/analytics/top-stores")
                        .param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /adoption → active tenants and per-feature rates")
    void adoption_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getAdoption()).thenReturn(new AdoptionReport(4, List.of(
                new FeatureAdoption("SALES", 4, 1.0), new FeatureAdoption("OFFLINE_SYNC", 1, 0.25))));

        mockMvc.perform(get("/api/v1/admin/analytics/adoption"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeTenants").value(4))
                .andExpect(jsonPath("$.data.features[0].feature").value("SALES"))
                .andExpect(jsonPath("$.data.features[0].tenantCount").value(4))
                .andExpect(jsonPath("$.data.features[0].rate").value(1.0))
                .andExpect(jsonPath("$.data.features[1].rate").value(0.25));
    }
}
