package com.keevo.identity.auth.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.identity.auth.application.service.AccountDeletionService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountControllerTest — Tests for {@link AccountController} (Story 14.5).
 *
 * <p>Uses {@code standaloneSetup} + {@code GlobalExceptionHandler} for RBAC testing.
 * (@PreAuthorize is not evaluated by standaloneSetup — relies on programmatic checks.)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountController")
class AccountControllerTest {

    @Mock private AccountDeletionService accountDeletionService;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private String schemaName;

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new AccountController(accountDeletionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        schemaName = "kv_abc123";

        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        // Set TenantContext for tenantId extraction
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(schemaName);
    }

    // ── POST /api/v1/account/delete ──────────────────────────────────────────

    @Test
    @DisplayName("POST /account/delete returns 200 for OWNER")
    void delete_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        doNothing().when(accountDeletionService).requestDeletion(any(UUID.class), anyString());

        mockMvc.perform(post("/api/v1/account/delete"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /account/delete returns 403 for EMPLOYEE")
    void delete_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(post("/api/v1/account/delete"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /account/delete returns 409 when already DELETION_PENDING")
    void delete_returns409_whenAlreadyPending() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.DELETION_ALREADY_REQUESTED,
                "Already pending")).when(accountDeletionService)
                .requestDeletion(any(UUID.class), anyString());

        mockMvc.perform(post("/api/v1/account/delete"))
                .andExpect(status().isConflict());
    }

    // ── POST /api/v1/account/delete/cancel ───────────────────────────────────

    @Test
    @DisplayName("POST /account/delete/cancel returns 200 for OWNER")
    void cancel_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        doNothing().when(accountDeletionService).cancelDeletion(any(UUID.class), anyString());

        mockMvc.perform(post("/api/v1/account/delete/cancel"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /account/delete/cancel returns 403 for EMPLOYEE")
    void cancel_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(post("/api/v1/account/delete/cancel"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /account/delete/cancel returns 409 when not pending")
    void cancel_returns409_whenNotPending() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.DELETION_NOT_PENDING,
                "Not pending")).when(accountDeletionService)
                .cancelDeletion(any(UUID.class), anyString());

        mockMvc.perform(post("/api/v1/account/delete/cancel"))
                .andExpect(status().isConflict());
    }
}
