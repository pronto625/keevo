package com.keevo.reporting.report.adapter.in.rest;

import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ReportControllerTest — TDD GREEN tests for the report history REST controller.
 * Story 7.2 — Task 3.1.
 */
@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock private GetReportHistoryUseCase getReportHistoryUseCase;
    @Mock private ResendReportUseCase resendReportUseCase;
    @InjectMocks private ReportController controller;

    private MockMvc mockMvc;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        ownerId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ownerId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private EndOfDayReport sampleReport() {
        return EndOfDayReport.createNew(
                "kv_test01", UUID.randomUUID(), "Boutique Test",
                null,
                ReportType.DAILY, LocalDate.now(), "Contenu rapport",
                100000, 5, false);
    }

    // ── GET /api/v1/reports ────────────────────────────────────────────────────

    @Test
    void GET_reports_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        Page<EndOfDayReport> page = new PageImpl<>(List.of(sampleReport()),
                PageRequest.of(0, 20), 1);
        when(getReportHistoryUseCase.getReportHistory(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].storeName").value("Boutique Test"));
    }

    @Test
    void GET_reports_returns200_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");
        Page<EndOfDayReport> emptyPage = new PageImpl<>(List.of(),
                PageRequest.of(0, 20), 0);
        when(getReportHistoryUseCase.getReportHistory(any())).thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk());
    }

    @Test
    void GET_reports_returns401_whenNotAuthenticated() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/reports/{id} ───────────────────────────────────────────────

    @Test
    void GET_reportById_returns200_whenFound() throws Exception {
        authenticateAs("OWNER");
        EndOfDayReport report = sampleReport();
        when(getReportHistoryUseCase.getReportById(any(UUID.class), any())).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/v1/reports/" + report.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportType").value("DAILY"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("PENDING"));
    }

    @Test
    void GET_reportById_returns404_whenNotFound() throws Exception {
        authenticateAs("OWNER");
        when(getReportHistoryUseCase.getReportById(any(UUID.class), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/reports/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_reportById_returns403_forEmployee_whenNotOwner() throws Exception {
        authenticateAs("EMPLOYEE");
        // Employee (ownerId principal) tries to access a report owned by someone else
        EndOfDayReport reportForOtherEmployee = sampleReport(); // actorId = null ≠ ownerId
        when(getReportHistoryUseCase.getReportById(any(UUID.class), any()))
                .thenReturn(Optional.of(reportForOtherEmployee));

        mockMvc.perform(get("/api/v1/reports/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/reports/{id}/resend ──────────────────────────────────────

    @Test
    void POST_resend_returns200_whenSuccess() throws Exception {
        authenticateAs("OWNER");
        doNothing().when(resendReportUseCase).resendReport(any());

        mockMvc.perform(post("/api/v1/reports/" + UUID.randomUUID() + "/resend"))
                .andExpect(status().isOk());
    }

    @Test
    void POST_resend_returns409_whenAlreadySent() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.REPORT_ALREADY_SENT, "Already sent"))
                .when(resendReportUseCase).resendReport(any());

        mockMvc.perform(post("/api/v1/reports/" + UUID.randomUUID() + "/resend"))
                .andExpect(status().isConflict());
    }

    @Test
    void POST_resend_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");
        mockMvc.perform(post("/api/v1/reports/" + UUID.randomUUID() + "/resend"))
                .andExpect(status().isForbidden());
        verify(resendReportUseCase, never()).resendReport(any());
    }
}
