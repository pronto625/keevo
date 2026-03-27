package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportQuery;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportUseCase;
import com.keevo.inventory.counting.domain.service.InventoryReportTextFormatter;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryReportController")
class InventoryReportControllerTest {

    @Mock private GenerateGapReportUseCase generateGapReportUseCase;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final InventoryReportTextFormatter textFormatter = new InventoryReportTextFormatter();
    private MockMvc mockMvc;

    private UUID actorId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        var controller = new InventoryReportController(
                generateGapReportUseCase, textFormatter, eventPublisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void getGapReport_shouldReturn200WithReport() throws Exception {
        authenticateAs("OWNER");
        when(generateGapReportUseCase.execute(any(GenerateGapReportQuery.class)))
                .thenReturn(sampleReport());

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.summary.totalCounted").value(2))
                .andExpect(jsonPath("$.data.summary.totalShortage").value(1))
                .andExpect(jsonPath("$.data.shortageRows[0].productName").value("Robe"));
    }

    @Test
    void getGapReport_sessionNotFound_shouldReturn404() throws Exception {
        authenticateAs("OWNER");
        when(generateGapReportUseCase.execute(any(GenerateGapReportQuery.class)))
                .thenThrow(new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "Not found"));

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGapReportText_shouldReturn200WithWhatsAppText() throws Exception {
        authenticateAs("OWNER");
        when(generateGapReportUseCase.execute(any(GenerateGapReportQuery.class)))
                .thenReturn(sampleReport());

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report/text", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString());
    }

    @Test
    void getGapReport_rbac_ownerAndEmployeeAllowed() throws Exception {
        // OWNER
        authenticateAs("OWNER");
        when(generateGapReportUseCase.execute(any(GenerateGapReportQuery.class)))
                .thenReturn(sampleReport());
        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                .andExpect(status().isOk());

        // EMPLOYEE
        authenticateAs("EMPLOYEE");
        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                .andExpect(status().isOk());
    }

    // ── Helpers ──

    private InventoryGapReport sampleReport() {
        InventoryGapRow concordant = new InventoryGapRow(
                UUID.randomUUID(), "Jeans", "KEV-JEA001", null,
                null, null, 10, 10, 0, 5000, 0);
        InventoryGapRow shortage = new InventoryGapRow(
                UUID.randomUUID(), "Robe", "KEV-ROB002", null,
                null, null, 10, 5, -5, 5000, 25000);

        return new InventoryGapReport(
                sessionId, UUID.randomUUID(), "Boutique Centrale",
                InventoryScope.FULL,
                new InventoryGapSummary(2, 1, 0, 1, 0, 25000),
                List.of(concordant), List.of(), List.of(shortage),
                actorId, Instant.now()
        );
    }
}
