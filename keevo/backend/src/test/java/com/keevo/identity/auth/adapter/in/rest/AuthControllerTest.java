package com.keevo.identity.auth.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.adapter.in.rest.dto.LoginRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RefreshRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.RegistrationRequest;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthControllerTest — @WebMvcTest slice tests for all auth endpoints.
 *
 * <p>JwtAuthFilter is mocked as a pass-through so requests reach the controller.
 * Security integration (missing/expired JWT → 401 before reaching controller)
 * is covered by {@link com.keevo.shared.infrastructure.security.JwtAuthFilterTest}.
 */
@WebMvcTest(AuthController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean RegisterUserUseCase   registerUserUseCase;
    @MockBean AuthenticateUserUseCase authenticateUserUseCase;
    @MockBean SelectTenantUseCase   selectTenantUseCase;
    @MockBean RefreshTokenUseCase   refreshTokenUseCase;
    @MockBean ChangePasswordUseCase changePasswordUseCase;
    @MockBean JwtAuthFilter         jwtAuthFilter;

    @BeforeEach
    void letFilterPassThrough() throws Exception {
        doAnswer(inv -> {
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    // ══════════════════════════════════════════════════
    // POST /api/v1/auth/register
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("POST /register → 201 Created with tenantCode and token")
    void register_returns201OnSuccess() throws Exception {
        RegistrationResult result = new RegistrationResult(
                "KV-ABC123", "STUB:user-id:tenant-id", "user-id", "tenant-id");

        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "SecurePass123!"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantCode").value("KV-ABC123"))
                .andExpect(jsonPath("$.token").value("STUB:user-id:tenant-id"));
    }

    @Test
    @DisplayName("POST /register → 409 Conflict when phone already registered")
    void register_returns409WhenPhoneTaken() throws Exception {
        when(registerUserUseCase.register(any(RegisterUserCommand.class)))
                .thenThrow(new DomainException(ErrorCode.USER_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "SecurePass123!"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("USER_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("POST /register → 422 when phone is blank")
    void register_returns422WhenPhoneBlank() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("", "SecurePass123!"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /register → 422 when password is too short")
    void register_returns422WhenPasswordTooShort() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "short"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ══════════════════════════════════════════════════
    // POST /api/v1/auth/login  (Story 1.7 step-1 — AC2 + AC5)
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("POST /login → 200 with loginToken and memberships (step 1)")
    void login_returns200WithLoginSessionResponse() throws Exception {
        UUID userId = UUID.randomUUID();
        LoginSessionResult result = new LoginSessionResult(
                "short-lived-login-token",
                List.of(new UserMembershipInfo("KV-ABC123", "My Shop", "OWNER", "kv_abc123")));
        when(authenticateUserUseCase.authenticate(any(AuthenticateUserCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("+22670000001", "SecurePass1!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginToken").value("short-lived-login-token"))
                .andExpect(jsonPath("$.memberships").isArray())
                .andExpect(jsonPath("$.memberships[0].tenantCode").value("KV-ABC123"))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"));
    }

    @Test
    @DisplayName("POST /login → 401 INVALID_CREDENTIALS on wrong password")
    void login_returns401OnInvalidCredentials() throws Exception {
        when(authenticateUserUseCase.authenticate(any()))
                .thenThrow(new DomainException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("+22670000001", "WrongPass1!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("POST /login → 401 ACCOUNT_LOCKED after 5 failed attempts")
    void login_returns401OnAccountLocked() throws Exception {
        when(authenticateUserUseCase.authenticate(any()))
                .thenThrow(new DomainException(ErrorCode.ACCOUNT_LOCKED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("+22670000001", "WrongPass1!"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("POST /login → 422 when phone is blank")
    void login_returns422WhenPhoneBlank() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("", "SecurePass1!"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ══════════════════════════════════════════════════
    // POST /api/v1/auth/select-tenant  (Story 1.7 step-2 — AC3)
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("POST /select-tenant → 200 with full access+refresh tokens (step 2)")
    void selectTenant_returns200WithFullTokens() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthTokens tokens = new AuthTokens("eyJhbGci.access.jwt", "opaque-refresh-token",
                86400L, userId, "kv_abc123", "OWNER");
        when(selectTenantUseCase.select(any(SelectTenantCommand.class))).thenReturn(tokens);

        String body = """
                {"loginToken":"short-lived-login-token","tenantCode":"KV-ABC123"}
                """;
        mockMvc.perform(post("/api/v1/auth/select-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("eyJhbGci.access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("opaque-refresh-token"))
                .andExpect(jsonPath("$.tenantId").value("kv_abc123"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    @DisplayName("POST /select-tenant → 401 TOKEN_INVALID when loginToken is an accessToken")
    void selectTenant_returns401OnInvalidScope() throws Exception {
        when(selectTenantUseCase.select(any()))
                .thenThrow(new DomainException(ErrorCode.TOKEN_INVALID));

        String body = """
                {"loginToken":"wrong-scope-access-token","tenantCode":"KV-ABC123"}
                """;
        mockMvc.perform(post("/api/v1/auth/select-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("TOKEN_INVALID"));
    }

    // ══════════════════════════════════════════════════
    // POST /api/v1/auth/refresh  (AC3)
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("POST /refresh → 200 with new tokens on valid refresh token")
    void refresh_returns200WithNewTokensOnSuccess() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthTokens tokens = new AuthTokens("new.access.token", "new-opaque-refresh-token",
                86400L, userId, "kv_abc123", "OWNER");
        when(refreshTokenUseCase.refresh("valid-refresh-token")).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest("valid-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("new-opaque-refresh-token"))
                .andExpect(jsonPath("$.expiresIn").value(86400));
    }

    @Test
    @DisplayName("POST /refresh → 401 REFRESH_TOKEN_INVALID on expired or unknown token")
    void refresh_returns401OnInvalidRefreshToken() throws Exception {
        when(refreshTokenUseCase.refresh(any()))
                .thenThrow(new DomainException(ErrorCode.REFRESH_TOKEN_INVALID));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest("expired-or-unknown-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("POST /refresh → 422 when refreshToken field is blank")
    void refresh_returns422WhenTokenBlank() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(""))))
                .andExpect(status().isUnprocessableEntity());
    }
}
