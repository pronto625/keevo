package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportQuery;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportUseCase;
import com.keevo.inventory.counting.domain.service.InventoryReportTextFormatter;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock private GenerateGapReportUseCase generateGapReportUseCase;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.keevo.inventory.counting.domain.port.out.InventorySessionRepository sessionRepository;

    private final InventoryReportTextFormatter textFormatter = new InventoryReportTextFormatter();
    private MockMvc mockMvc;

    private UUID actorId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        var controller = new InventoryReportController(
                generateGapReportUseCase, textFormatter, eventPublisher, sessionRepository);
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

        // EMPLOYEE of the session's store (Story 16.13 reprise: a member only reads its own store's sessions)
        sessionInReportStore();
        authenticateAsMember("EMPLOYEE", reportStore);
        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                .andExpect(status().isOk());
    }

    private void sessionInReportStore() {
        var session = org.mockito.Mockito.mock(com.keevo.inventory.counting.domain.model.InventorySession.class);
        org.mockito.Mockito.lenient().when(session.getStoreId()).thenReturn(reportStore);
        org.mockito.Mockito.lenient().when(sessionRepository.findById(sessionId))
                .thenReturn(java.util.Optional.of(session));
    }

    @Test
    void getGapReport_memberOfAnotherStore_403_beforeTheReportIsComputed() throws Exception {
        sessionInReportStore();
        for (String role : List.of("EMPLOYEE", "MANAGER", "VIEWER")) {
            authenticateAsMember(role, UUID.randomUUID());
            mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report", sessionId))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/gap-report/text", sessionId))
                    .andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(generateGapReportUseCase);
    }

    private final UUID reportStore = UUID.randomUUID();

    private void authenticateAsMember(String role, UUID store) {
        var auth = new UsernamePasswordAuthenticationToken(actorId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new com.keevo.shared.infrastructure.security.AuthDetails("Awa", store));
        SecurityContextHolder.getContext().setAuthentication(auth);
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
                sessionId, reportStore, "Boutique Centrale",
                InventoryScope.FULL,
                new InventoryGapSummary(2, 1, 0, 1, 0, 25000),
                List.of(concordant), List.of(), List.of(shortage),
                actorId, Instant.now(),
                "IN_PROGRESS"
        );
    }
}
