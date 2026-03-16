package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.employee.domain.port.in.ChangePasswordCommand;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — Change Password (Story 3.5)")
class AuthControllerChangePasswordTest {

    @Mock ChangePasswordUseCase changePasswordUseCase;

    // We need a partial AuthController with changePasswordUseCase injected.
    // The new constructor will add ChangePasswordUseCase as an optional parameter.
    // For this test, we build a standalone controller that only covers the change-password endpoint.

    MockMvc mockMvc;
    UUID actorId;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        // AuthController will gain a ChangePasswordUseCase dependency
        AuthController authController = new AuthController(null, null, null, null, changePasswordUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null, List.of()));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password returns 200 with new tokens")
    void changePassword_returns200_withNewTokens() throws Exception {
        AuthTokens newTokens = new AuthTokens(
                "new-access-jwt", "new-refresh-token", 86400,
                actorId, "kv_abc123", "EMPLOYEE");
        when(changePasswordUseCase.execute(any())).thenReturn(newTokens);

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"TmpPass12345","newPassword":"SecureNew1!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));

        ArgumentCaptor<ChangePasswordCommand> captor = ArgumentCaptor.forClass(ChangePasswordCommand.class);
        verify(changePasswordUseCase).execute(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().currentPassword()).isEqualTo("TmpPass12345");
        assertThat(captor.getValue().newPassword()).isEqualTo("SecureNew1!");
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password returns 401 on wrong current password")
    void changePassword_returns401_whenCurrentPasswordWrong() throws Exception {
        when(changePasswordUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.INVALID_CREDENTIALS, "wrong current password"));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrongpassword","newPassword":"SecureNew1!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password returns 422 on too-short password")
    void changePassword_returns422_whenPasswordTooShort() throws Exception {
        when(changePasswordUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_FAILED, "password too short"));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"TmpPass12345","newPassword":"abcdefgh"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));
    }
}
