package com.keevo.identity.onboarding.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.SendTestReportUseCase;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ReportPreferencesControllerTest — Story 7.5 Task 1.3
 * Standalone MockMvc tests for PUT /tenant/report-preferences and POST /tenant/report-test.
 */
@ExtendWith(MockitoExtension.class)
class ReportPreferencesControllerTest {

    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private UpdateReportPreferencesUseCase updateReportPreferencesUseCase;
    @Mock private SendTestReportUseCase sendTestReportUseCase;

    @InjectMocks private TenantPreferencesController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private MockedStatic<TenantContext> tenantContextMock;

    private static final String TENANT_ID = "kv_ctx_test";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getCurrentTenant).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        tenantContextMock.close();
    }

    // ── PUT /api/v1/tenant/report-preferences ─────────────────────────────────

    @Test
    void PUT_reportPreferences_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        TenantPreferences updatedPrefs = samplePrefs();
        when(updateReportPreferencesUseCase.update(any())).thenReturn(updatedPrefs);

        mockMvc.perform(put("/api/v1/tenant/report-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPutBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eodReportTime").value("22:00:00"))
                .andExpect(jsonPath("$.data.eodReportEnabled").value(true));
    }

    @Test
    void PUT_reportPreferences_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(put("/api/v1/tenant/report-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPutBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void PUT_reportPreferences_returns401_withoutAuth() throws Exception {
        // No authentication set
        SecurityContextHolder.clearContext();

        mockMvc.perform(put("/api/v1/tenant/report-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPutBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void PUT_reportPreferences_returns400_forInvalidEodTime() throws Exception {
        authenticateAs("OWNER");

        String invalidBody = """
                {
                  "eodReportEnabled": true,
                  "eodReportChannel": "WHATSAPP",
                  "eodReportTime": "25:99:00",
                  "weeklyReportEnabled": true,
                  "weeklyReportDay": 0,
                  "weeklyReportTime": "20:00:00",
                  "weeklyReportChannel": "WHATSAPP",
                  "inventoryReportEnabled": true,
                  "inventoryReportChannel": "WHATSAPP",
                  "stockAlertEnabled": true,
                  "stockAlertChannel": "PUSH"
                }""";

        mockMvc.perform(put("/api/v1/tenant/report-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /api/v1/tenant/report-test ───────────────────────────────────────

    @Test
    void POST_reportTest_returns200_withTestSentTrue_forOwner() throws Exception {
        authenticateAs("OWNER");
        when(sendTestReportUseCase.sendTestReport(anyString()))
                .thenReturn(new SendTestReportUseCase.TestReportResult(true));

        mockMvc.perform(post("/api/v1/tenant/report-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSent").value(true));
    }

    @Test
    void POST_reportTest_returns200_withTestSentFalse_whenWhatsAppFails() throws Exception {
        authenticateAs("OWNER");
        when(sendTestReportUseCase.sendTestReport(anyString()))
                .thenReturn(new SendTestReportUseCase.TestReportResult(false));

        mockMvc.perform(post("/api/v1/tenant/report-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSent").value(false));
    }

    @Test
    void POST_reportTest_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(post("/api/v1/tenant/report-test"))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void authenticateAs(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String validPutBody() {
        return """
                {
                  "eodReportEnabled": true,
                  "eodReportChannel": "WHATSAPP",
                  "eodReportTime": "22:00:00",
                  "weeklyReportEnabled": true,
                  "weeklyReportDay": 0,
                  "weeklyReportTime": "20:00:00",
                  "weeklyReportChannel": "WHATSAPP",
                  "inventoryReportEnabled": true,
                  "inventoryReportChannel": "WHATSAPP",
                  "stockAlertEnabled": true,
                  "stockAlertChannel": "PUSH"
                }""";
    }

    private TenantPreferences samplePrefs() {
        return new TenantPreferences(
                UUID.randomUUID(), SectorType.OTHER, "22:00:00", true, Instant.now(),
                true, ReportChannel.WHATSAPP, true, 0, "20:00:00", ReportChannel.WHATSAPP,
                true, ReportChannel.WHATSAPP, StockAlertChannel.PUSH
        );
    }
}
