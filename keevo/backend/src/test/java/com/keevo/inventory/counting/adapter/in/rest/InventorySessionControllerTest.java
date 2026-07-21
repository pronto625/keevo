package com.keevo.inventory.counting.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventorySessionController")
class InventorySessionControllerTest {

    @Mock private CreateInventorySessionUseCase createSessionUseCase;
    @Mock private GetActiveSessionUseCase getActiveSessionUseCase;
    @Mock private CancelInventorySessionUseCase cancelSessionUseCase;
    @Mock private ListInventorySessionsUseCase listSessionsUseCase;
    @Mock private ValidateInventoryUseCase validateInventoryUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;

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
        storeId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void mockJwtRole(String role) {
        authenticateAs(role);
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(
                Jwts.claims().subject(actorId.toString()).add("role", role).build());
        when(jwtTokenProvider.extractRole(any(Claims.class))).thenReturn(role);
    }

    @Test
    void POST_sessions_shouldReturn201() throws Exception {
        authenticateAs("OWNER");
        var session = InventorySession.create(storeId, InventoryScope.FULL, null, actorId);
        when(createSessionUseCase.execute(any())).thenReturn(session);

        mockMvc.perform(post("/api/v1/inventory/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new java.util.LinkedHashMap<>() {{
                                    put("storeId", storeId);
                                    put("scope", "FULL");
                                }})))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value("FULL"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void POST_sessions_whenAlreadyActive_shouldReturn409() throws Exception {
        authenticateAs("OWNER");
        when(createSessionUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_ALREADY_ACTIVE, "Active session exists"));

        mockMvc.perform(post("/api/v1/inventory/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"" + storeId + "\",\"scope\":\"FULL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("INVENTORY_SESSION_ALREADY_ACTIVE"));
    }

    @Test
    void GET_sessions_active_shouldReturn200() throws Exception {
        authenticateAs("OWNER");
        var session = InventorySession.create(storeId, InventoryScope.FULL, null, actorId);
        when(getActiveSessionUseCase.execute(any())).thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/v1/inventory/sessions/active")
                        .param("storeId", storeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    void GET_sessions_active_whenNone_shouldReturn404() throws Exception {
        authenticateAs("OWNER");
        when(getActiveSessionUseCase.execute(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/inventory/sessions/active")
                        .param("storeId", storeId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("INVENTORY_SESSION_NOT_FOUND"));
    }

    @Test
    void POST_sessions_cancel_shouldReturn200() throws Exception {
        mockJwtRole("OWNER");
        var session = InventorySession.create(storeId, InventoryScope.FULL, null, actorId);
        session.cancel(actorId);
        when(cancelSessionUseCase.execute(any())).thenReturn(session);

        mockMvc.perform(post("/api/v1/inventory/sessions/" + session.getId() + "/cancel")
                        .header("Authorization", "Bearer mock-token"))
                .andExpect(status().isOk());
    }

    @Test
    void POST_sessions_cancel_employeeCannotCancelOthers_shouldReturn403() throws Exception {
        mockJwtRole("EMPLOYEE");
        UUID sessionId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.FORBIDDEN, "Cannot cancel"))
                .when(cancelSessionUseCase).execute(any());

        mockMvc.perform(post("/api/v1/inventory/sessions/" + sessionId + "/cancel")
                        .header("Authorization", "Bearer mock-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
    }

    @Test
    void GET_sessions_list_shouldReturnPaginated() throws Exception {
        authenticateAs("OWNER");
        var s1 = InventorySession.create(storeId, InventoryScope.FULL, null, actorId);
        var s2 = InventorySession.create(storeId, InventoryScope.PARTIAL, List.of(UUID.randomUUID()), actorId);
        when(listSessionsUseCase.execute(any())).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/v1/inventory/sessions")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void POST_sessions_withoutRequiredFields_shouldReturn422() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(post("/api/v1/inventory/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Story 12.6 — EMPLOYEE store scope (FR36) ──────────────────────

    @Test
    void POST_sessions_employeeOwnStore_returns201() throws Exception {
        authenticateAs("EMPLOYEE");
        // Simulate JwtAuthFilter setting details = assigned storeId
        UsernamePasswordAuthenticationToken auth =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        auth.setDetails(storeId);

        var session = InventorySession.create(storeId, InventoryScope.FULL, null, actorId);
        when(createSessionUseCase.execute(any())).thenReturn(session);

        mockMvc.perform(post("/api/v1/inventory/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new java.util.LinkedHashMap<>() {{
                                    put("storeId", storeId);
                                    put("scope", "FULL");
                                }})))
                .andExpect(status().isCreated());
    }

    @Test
    void POST_sessions_employeeOtherStore_returns403() throws Exception {
        authenticateAs("EMPLOYEE");
        UUID assignedStoreId = UUID.randomUUID();
        UsernamePasswordAuthenticationToken auth =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        auth.setDetails(assignedStoreId);

        UUID otherStoreId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new java.util.LinkedHashMap<>() {{
                                    put("storeId", otherStoreId);
                                    put("scope", "FULL");
                                }})))
                .andExpect(status().isForbidden());

        verify(createSessionUseCase, never()).execute(any());
    }
}
