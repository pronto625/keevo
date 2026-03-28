package com.keevo.inventory.counting.adapter.in.rest;

import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.model.ValidateInventoryResult;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventorySessionController — POST /{id}/validate")
class InventorySessionControllerValidateTest {

    @Mock private CreateInventorySessionUseCase createSessionUseCase;
    @Mock private GetActiveSessionUseCase getActiveSessionUseCase;
    @Mock private CancelInventorySessionUseCase cancelSessionUseCase;
    @Mock private ListInventorySessionsUseCase listSessionsUseCase;
    @Mock private ValidateInventoryUseCase validateInventoryUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        var controller = new InventorySessionController(
                createSessionUseCase, getActiveSessionUseCase,
                cancelSessionUseCase, listSessionsUseCase,
                validateInventoryUseCase, jwtTokenProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void validateInventory_shouldReturn200WithResult() throws Exception {
        authenticateAs("OWNER");
        UUID sessionId = UUID.randomUUID();
        Instant completedAt = Instant.now();

        when(validateInventoryUseCase.execute(any())).thenReturn(
                new ValidateInventoryResult(sessionId, 5, InventorySessionStatus.VALIDATED, completedAt));

        mockMvc.perform(post("/api/v1/inventory/sessions/{id}/validate", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adjustmentsApplied").value(5))
                .andExpect(jsonPath("$.data.status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()));
    }

    @Test
    void validateInventory_sessionNotFound_shouldReturn404() throws Exception {
        authenticateAs("OWNER");
        UUID sessionId = UUID.randomUUID();

        when(validateInventoryUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session not found: " + sessionId));

        mockMvc.perform(post("/api/v1/inventory/sessions/{id}/validate", sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    void validateInventory_sessionNotInProgress_shouldReturn409() throws Exception {
        authenticateAs("OWNER");
        UUID sessionId = UUID.randomUUID();

        when(validateInventoryUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                        "Cannot validate session in status: VALIDATED"));

        mockMvc.perform(post("/api/v1/inventory/sessions/{id}/validate", sessionId))
                .andExpect(status().isConflict());
    }

    @Test
    void validateInventory_ownerAllowed_shouldReturn200() throws Exception {
        authenticateAs("OWNER");
        UUID sessionId = UUID.randomUUID();

        when(validateInventoryUseCase.execute(any())).thenReturn(
                new ValidateInventoryResult(sessionId, 3, InventorySessionStatus.VALIDATED, Instant.now()));

        mockMvc.perform(post("/api/v1/inventory/sessions/{id}/validate", sessionId))
                .andExpect(status().isOk());
    }

    @Test
    void validateInventory_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory/sessions/{id}/validate", sessionId))
                .andExpect(status().isForbidden());
    }
}
