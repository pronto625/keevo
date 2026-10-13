package com.keevo.admin.revenue.adapter.in.rest;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.keevo.admin.revenue.application.service.AdminRevenueService;
import com.keevo.admin.revenue.domain.model.*;
import com.keevo.admin.revenue.domain.port.in.RevenueRangeQuery;
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
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRevenueController")
class AdminRevenueControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Mock AdminRevenueService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminRevenueController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(
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

    private static ChurnReport churnReport() {
        return new ChurnReport(YearMonth.of(2026, 9), 4, 0.5, List.of(
                new ChurnedTenant(TENANT_ID, "Boutique; \"Test\"", LocalDate.of(2026, 9, 12), 8.3, 42_000L),
                new ChurnedTenant(UUID.randomUUID(), null, LocalDate.of(2026, 9, 20), null, 0L)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/admin/revenue/summary",
            "/api/v1/admin/revenue/trend",
            "/api/v1/admin/revenue/churn?month=2026-09",
            "/api/v1/admin/revenue/churn/export?month=2026-09"})
    @DisplayName("OWNER → 403 FORBIDDEN on every endpoint")
    void ownerIsForbidden(String url) throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get(url))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("GET /summary → 200 with every field read by the dashboard")
    void summary_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getSummary(new RevenueRangeQuery("2026-10-01", "2026-10-31"))).thenReturn(new RevenueSummary(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                15_000, 180_000, 10_000, 50.0, 1, 3, 2, 2, 1, 1, 1, 4, 0.25, 1, 2, 0.5, 15_000));

        mockMvc.perform(get("/api/v1/admin/revenue/summary").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/revenue.ts (RevenueSummary)
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.to").value("2026-10-31"))
                .andExpect(jsonPath("$.data.mrr").value(15000))
                .andExpect(jsonPath("$.data.arr").value(180000))
                .andExpect(jsonPath("$.data.mrrPreviousMonth").value(10000))
                .andExpect(jsonPath("$.data.mrrTrendPercent").value(50.0))
                .andExpect(jsonPath("$.data.payingTenants").value(1))
                .andExpect(jsonPath("$.data.freeTenants").value(3))
                .andExpect(jsonPath("$.data.trialTenants").value(2))
                .andExpect(jsonPath("$.data.premiumTenants").value(2))
                .andExpect(jsonPath("$.data.premiumWithoutPayment").value(1))
                .andExpect(jsonPath("$.data.newPayingTenants").value(1))
                .andExpect(jsonPath("$.data.churnedTenants").value(1))
                .andExpect(jsonPath("$.data.churnBase").value(4))
                .andExpect(jsonPath("$.data.churnRate").value(0.25))
                .andExpect(jsonPath("$.data.convertedTenants").value(1))
                .andExpect(jsonPath("$.data.trialsEnded").value(2))
                .andExpect(jsonPath("$.data.conversionRate").value(0.5))
                .andExpect(jsonPath("$.data.revenueCollected").value(15000));
    }

    @Test
    @DisplayName("GET /summary invalid range → 422 VALIDATION_ERROR")
    void summary_invalid_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getSummary(new RevenueRangeQuery("2026-02-01", "2026-01-01")))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "La date de début doit précéder la date de fin"));

        mockMvc.perform(get("/api/v1/admin/revenue/summary").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /trend → 200 with YYYY-MM months")
    void trend_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getTrend()).thenReturn(List.of(
                new RevenueTrendPoint(YearMonth.of(2026, 9), 10_000, 1, 1, 0),
                new RevenueTrendPoint(YearMonth.of(2026, 10), 15_000, 2, 1, 1)));

        mockMvc.perform(get("/api/v1/admin/revenue/trend"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/revenue.ts (RevenueTrendPoint)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].month").value("2026-10"))
                .andExpect(jsonPath("$.data[1].mrr").value(15000))
                .andExpect(jsonPath("$.data[1].payingTenants").value(2))
                .andExpect(jsonPath("$.data[1].newPayingTenants").value(1))
                .andExpect(jsonPath("$.data[1].churnedTenants").value(1));
    }

    @Test
    @DisplayName("GET /churn → 200, « Sans nom » for an unnamed tenant, no tenant id")
    void churn_ok() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getChurn("2026-09")).thenReturn(churnReport());

        MvcResult result = mockMvc.perform(get("/api/v1/admin/revenue/churn").param("month", "2026-09"))
                .andExpect(status().isOk())
                // Contract with keevo/dashboard/src/types/revenue.ts (ChurnReport)
                .andExpect(jsonPath("$.data.month").value("2026-09"))
                .andExpect(jsonPath("$.data.churnedCount").value(2))
                .andExpect(jsonPath("$.data.payingAtMonthStart").value(4))
                .andExpect(jsonPath("$.data.churnRate").value(0.5))
                .andExpect(jsonPath("$.data.tenants[0].tenantName").value("Boutique; \"Test\""))
                .andExpect(jsonPath("$.data.tenants[0].planEndDate").value("2026-09-12"))
                .andExpect(jsonPath("$.data.tenants[0].tenureMonths").value(8.3))
                .andExpect(jsonPath("$.data.tenants[0].lastGmv30d").value(42000))
                .andExpect(jsonPath("$.data.tenants[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.tenants[1].tenantName").value("Sans nom"))
                .andExpect(jsonPath("$.data.tenants[1].tenureMonths").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(TENANT_ID.toString());
    }

    @Test
    @DisplayName("GET /churn bad month → 422 VALIDATION_ERROR")
    void churn_badMonth_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getChurn("2026-13"))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "month doit être au format YYYY-MM"));

        mockMvc.perform(get("/api/v1/admin/revenue/churn").param("month", "2026-13"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /churn/export bad month → 422 VALIDATION_ERROR as a JSON error body (no CSV)")
    void export_badMonth_returns422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getChurn("bad"))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "month doit être au format YYYY-MM"));

        MvcResult result = mockMvc.perform(get("/api/v1/admin/revenue/churn/export").param("month", "bad"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn();
        assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    @Test
    @DisplayName("GET /churn/export → CSV with BOM, filename, French columns, ';', no id")
    void export_csv() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(service.getChurn("2026-09")).thenReturn(churnReport());

        MvcResult result = mockMvc.perform(get("/api/v1/admin/revenue/churn/export").param("month", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"keevo-churn-2026-09.csv\""))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo(
                "Entreprise;Fin du plan;Ancienneté payante (mois);CA des 30 jours avant la fin (XAF)");
        assertThat(lines[1]).isEqualTo("\"Boutique; \"\"Test\"\"\";12/09/2026;8,3;42000");
        assertThat(lines[2]).isEqualTo("Sans nom;20/09/2026;;0");
        assertThat(csv).doesNotContain(TENANT_ID.toString())
                .doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
    }

    @Test
    @DisplayName("CSV escaping neutralises spreadsheet formulas")
    void csvEscape_formula() {
        assertThat(ChurnCsvWriter.escape("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(ChurnCsvWriter.escape("Boutique")).isEqualTo("Boutique");
    }
}
