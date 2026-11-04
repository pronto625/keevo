package com.keevo.identity.auth.adapter.in.rest;

import org.springframework.security.core.context.SecurityContextHolder;
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
import com.keevo.identity.auth.domain.port.in.ResetPasswordUseCase;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetUseCase;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean RegisterUserUseCase   registerUserUseCase;
    @MockitoBean AuthenticateUserUseCase authenticateUserUseCase;
    @MockitoBean SelectTenantUseCase   selectTenantUseCase;
    @MockitoBean RefreshTokenUseCase   refreshTokenUseCase;
    @MockitoBean ChangePasswordUseCase changePasswordUseCase;
    @MockitoBean GetUserProfileUseCase getUserProfileUseCase;
    @MockitoBean RequestPasswordResetUseCase requestPasswordResetUseCase;
    @MockitoBean ResetPasswordUseCase  resetPasswordUseCase;
    @MockitoBean com.keevo.identity.auth.application.service.RefreshRateLimiter refreshRateLimiter;
    @MockitoBean com.keevo.identity.auth.domain.port.in.RequestOtpUseCase requestOtpUseCase;
    @MockitoBean com.keevo.identity.auth.domain.port.in.VerifyOtpUseCase verifyOtpUseCase;
    @MockitoBean com.keevo.identity.auth.domain.port.in.CreateCompanyUseCase createCompanyUseCase;
    @MockitoBean com.keevo.identity.auth.domain.port.in.SocialSignInUseCase socialSignInUseCase;
    @MockitoBean JwtAuthFilter         jwtAuthFilter;

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
                "KV-ABC123", "STUB:user-id:tenant-id", "user-id", "tenant-id", "refresh-tok");

        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "SecurePass123!"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantCode").value("KV-ABC123"))
                .andExpect(jsonPath("$.token").value("STUB:user-id:tenant-id"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-tok"));
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
        when(refreshTokenUseCase.refresh(eq("valid-refresh-token"), any())).thenReturn(tokens);

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
        when(refreshTokenUseCase.refresh(any(), any()))
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

    // ══════════════════════════════════════════════════
    // POST /api/v1/auth/logout
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("POST /logout → 204 without access token and revokes the refresh token")
    void logout_returns204AndRevokes() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("some-refresh-token"))))
                .andExpect(status().isNoContent());

        verify(refreshTokenUseCase).revoke("some-refresh-token");
    }

    @Test
    @DisplayName("POST /logout → 204 with a blank token or no body (always 204, no oracle)")
    void logout_returns204WhenTokenBlankOrMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(""))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(refreshTokenUseCase, never()).revoke(any());
    }

    // ══════════════════════════════════════════════════
    // Story 19.2 — sign-in code and company creation
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("19.2 POST /otp/request → 200 {expiresIn:300}, channel, IP and Accept-Language passed down")
    void otpRequest_returns200() throws Exception {
        when(requestOtpUseCase.request(any())).thenReturn(300);

        mockMvc.perform(post("/api/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "en-US")
                        .header("X-Forwarded-For", "41.202.1.1")
                        .content("{\"identifier\":\"Ana@Ex.com\",\"channel\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"expiresIn\":300}", true));

        org.mockito.ArgumentCaptor<com.keevo.identity.auth.domain.port.in.RequestOtpCommand> cmd =
                org.mockito.ArgumentCaptor.forClass(com.keevo.identity.auth.domain.port.in.RequestOtpCommand.class);
        verify(requestOtpUseCase).request(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().identifier()).isEqualTo("Ana@Ex.com");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().channel())
                .isEqualTo(com.keevo.identity.auth.domain.model.OtpChannel.EMAIL);
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().clientIp()).isEqualTo("41.202.1.1");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().language()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("19.2 POST /otp/request → 422 for an unknown channel, PHONE_COUNTRY_NOT_SUPPORTED, 429 limits")
    void otpRequest_errors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"+237699001122\",\"channel\":\"PIGEON\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));

        when(requestOtpUseCase.request(any())).thenThrow(new DomainException(ErrorCode.PHONE_COUNTRY_NOT_SUPPORTED));
        mockMvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"+33612345678\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("PHONE_COUNTRY_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.error").value("Ce pays n'est pas encore pris en charge"));

        org.mockito.Mockito.reset(requestOtpUseCase);
        when(requestOtpUseCase.request(any())).thenThrow(new DomainException(ErrorCode.OTP_TOO_MANY_REQUESTS));
        mockMvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"+237699001122\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.domainCode").value("OTP_TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("19.2 POST /otp/verify → 200 like /login; 401 OTP_INVALID; 429 OTP_TOO_MANY_ATTEMPTS")
    void otpVerify() throws Exception {
        when(verifyOtpUseCase.verify(any())).thenReturn(new LoginSessionResult("login-tok", List.of()));
        mockMvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ana@ex.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginToken").value("login-tok"))
                .andExpect(jsonPath("$.memberships").isEmpty());

        org.mockito.Mockito.reset(verifyOtpUseCase);
        when(verifyOtpUseCase.verify(any())).thenThrow(new DomainException(ErrorCode.OTP_INVALID));
        mockMvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ana@ex.com\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("OTP_INVALID"));

        org.mockito.Mockito.reset(verifyOtpUseCase);
        when(verifyOtpUseCase.verify(any())).thenThrow(new DomainException(ErrorCode.OTP_TOO_MANY_ATTEMPTS));
        mockMvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ana@ex.com\",\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.domainCode").value("OTP_TOO_MANY_ATTEMPTS"));
    }

    @Test
    @DisplayName("19.2 POST /create-company → 201 RegistrationResponse; 409 COMPANY_ALREADY_EXISTS")
    void createCompany() throws Exception {
        when(createCompanyUseCase.create(any())).thenReturn(
                new RegistrationResult("KV-ABC123", "access", "user-id", "tenant-id", "refresh"));
        mockMvc.perform(post("/api/v1/auth/create-company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginToken\":\"login-tok\",\"deviceId\":\"dev-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantCode").value("KV-ABC123"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));

        org.mockito.Mockito.reset(createCompanyUseCase);
        when(createCompanyUseCase.create(any())).thenThrow(new DomainException(ErrorCode.COMPANY_ALREADY_EXISTS));
        mockMvc.perform(post("/api/v1/auth/create-company").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginToken\":\"login-tok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("COMPANY_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error").value("Vous avez déjà une entreprise"));
    }

    @Test
    @DisplayName("19.3 POST /social/google → 200 like /login, command carries provider, token, names and IP")
    void socialGoogle() throws Exception {
        when(socialSignInUseCase.signIn(any())).thenReturn(new LoginSessionResult("login-tok",
                List.of(new UserMembershipInfo("KV-ABC123", "Boutique", "OWNER", "kv_abc123"))));
        mockMvc.perform(post("/api/v1/auth/social/google").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "10.0.0.9")
                        .content("{\"idToken\":\"g-token\",\"firstName\":\"Ana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginToken").value("login-tok"))
                .andExpect(jsonPath("$.memberships[0].tenantCode").value("KV-ABC123"));
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.identity.auth.domain.port.in.SocialSignInCommand.class);
        verify(socialSignInUseCase).signIn(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(
                new com.keevo.identity.auth.domain.port.in.SocialSignInCommand(
                        com.keevo.identity.auth.domain.model.SocialProvider.GOOGLE, "g-token", "Ana", null,
                        "10.0.0.9"));
    }

    @Test
    @DisplayName("19.3 POST /social/apple → 200; 401 SOCIAL_TOKEN_INVALID; 409 EMAIL_ALREADY_EXISTS; 429; 422")
    void socialApple() throws Exception {
        when(socialSignInUseCase.signIn(any())).thenReturn(new LoginSessionResult("login-tok", List.of()));
        mockMvc.perform(post("/api/v1/auth/social/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"a-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships").isEmpty());

        org.mockito.Mockito.reset(socialSignInUseCase);
        when(socialSignInUseCase.signIn(any())).thenThrow(new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID));
        mockMvc.perform(post("/api/v1/auth/social/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"a-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.domainCode").value("SOCIAL_TOKEN_INVALID"))
                .andExpect(jsonPath("$.error").value("Connexion Google ou Apple refusée. Réessayez"));

        org.mockito.Mockito.reset(socialSignInUseCase);
        when(socialSignInUseCase.signIn(any())).thenThrow(new DomainException(ErrorCode.EMAIL_ALREADY_EXISTS));
        mockMvc.perform(post("/api/v1/auth/social/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"a-token\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("EMAIL_ALREADY_EXISTS"));

        org.mockito.Mockito.reset(socialSignInUseCase);
        when(socialSignInUseCase.signIn(any())).thenThrow(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED));
        mockMvc.perform(post("/api/v1/auth/social/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"a-token\"}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/social/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
