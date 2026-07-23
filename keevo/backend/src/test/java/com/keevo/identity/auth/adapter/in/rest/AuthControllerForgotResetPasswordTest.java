package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.domain.port.in.RequestPasswordResetUseCase;
import com.keevo.identity.auth.domain.port.in.ResetPasswordUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthControllerForgotResetPasswordTest — Story 14.12 controller tests.
 *
 * <p>These endpoints are PUBLIC — no SecurityContextHolder setup needed
 * (unlike AuthControllerChangePasswordTest).
 *
 * <p>Mirror of AuthControllerChangePasswordTest pattern (MockMvc standalone).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — Forgot/Reset Password (Story 14.12)")
class AuthControllerForgotResetPasswordTest {

    @Mock RequestPasswordResetUseCase requestPasswordResetUseCase;
    @Mock ResetPasswordUseCase resetPasswordUseCase;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController authController = new AuthController(
                null, null, null, null, null, null,
                requestPasswordResetUseCase, resetPasswordUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        // NO SecurityContextHolder setup — these endpoints are PUBLIC (D9)
    }

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password returns 200 always")
    void shouldReturn200AlwaysOnForgotPassword() throws Exception {
        doNothing().when(requestPasswordResetUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "+237600000000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));

        verify(requestPasswordResetUseCase).execute(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/reset-password returns 200 on success")
    void shouldReturn200OnResetPasswordSuccess() throws Exception {
        doNothing().when(resetPasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "+237600000000", "code": "123456", "newPassword": "newPass1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));

        verify(resetPasswordUseCase).execute(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/reset-password returns 422 on invalid/expired code")
    void shouldReturn422OnInvalidCode() throws Exception {
        doThrow(new DomainException(ErrorCode.INVALID_OR_EXPIRED_CODE))
                .when(resetPasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "+237600000000", "code": "000000", "newPassword": "newPass1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/reset-password returns 422 on locked token")
    void shouldReturn422OnLockedCode() throws Exception {
        doThrow(new DomainException(ErrorCode.CODE_LOCKED))
                .when(resetPasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "+237600000000", "code": "000000", "newPassword": "newPass1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("CODE_LOCKED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/reset-password returns 422 on weak new password")
    void shouldReturn422OnWeakNewPassword() throws Exception {
        doThrow(new DomainException(ErrorCode.VALIDATION_FAILED))
                .when(resetPasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber": "+237600000000", "code": "123456", "newPassword": "short"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));
    }
}
