package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.application.service.RefreshRateLimiter;
import com.keevo.identity.auth.domain.model.AuthTokens;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserCommand;
import com.keevo.identity.auth.domain.port.in.AuthenticateUserUseCase;
import com.keevo.identity.auth.domain.port.in.CreateCompanyUseCase;
import com.keevo.identity.auth.domain.port.in.GetUserProfileUseCase;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.RefreshTokenUseCase;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RequestOtpUseCase;
import com.keevo.identity.auth.domain.port.in.RequestPasswordResetUseCase;
import com.keevo.identity.auth.domain.port.in.ResetPasswordUseCase;
import com.keevo.identity.auth.domain.port.in.SelectTenantCommand;
import com.keevo.identity.auth.domain.port.in.SelectTenantUseCase;
import com.keevo.identity.auth.domain.port.in.SocialSignInUseCase;
import com.keevo.identity.auth.domain.port.in.VerifyOtpUseCase;
import com.keevo.identity.employee.domain.port.in.ChangePasswordUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 19.10 — legacy-client guard at the HTTP edge: without a non-blank {@code X-App-Version} header the login lists
 * (login, otp/verify, social) leave out MANAGER / VIEWER memberships and select-tenant / refresh pass the legacy flag
 * to their use case; with the header, or with the guard switched off, nothing changes. OWNER / EMPLOYEE untouched.
 */
@DisplayName("19.10 — AuthController legacy-client guard")
class AuthControllerLegacyClientTest {

    private static final String LOGIN = "{\"phoneNumber\":\"+237670000001\",\"password\":\"SecurePass1!\"}";
    private static final String SELECT = "{\"loginToken\":\"login-tok\",\"tenantCode\":\"KV-ABC123\"}";
    private static final String REFRESH = "{\"refreshToken\":\"refresh-tok\"}";

    static final LoginSessionResult MIXED = new LoginSessionResult("login-tok", List.of(
            new UserMembershipInfo("KV-AAA111", "Siège", "OWNER", "kv_aaa111"),
            new UserMembershipInfo("KV-BBB222", "Boutique", "VIEWER", "kv_bbb222"),
            new UserMembershipInfo("KV-CCC333", "Dépôt", "MANAGER", "kv_ccc333"),
            new UserMembershipInfo("KV-DDD444", "Kiosque", "EMPLOYEE", "kv_ddd444")));

    static final LoginSessionResult VIEWER_ONLY = new LoginSessionResult("login-tok", List.of(
            new UserMembershipInfo("KV-BBB222", "Boutique", "VIEWER", "kv_bbb222")));

    abstract static class Base {
        @Autowired MockMvc mockMvc;

        @MockitoBean RegisterUserUseCase registerUserUseCase;
        @MockitoBean AuthenticateUserUseCase authenticateUserUseCase;
        @MockitoBean SelectTenantUseCase selectTenantUseCase;
        @MockitoBean RefreshTokenUseCase refreshTokenUseCase;
        @MockitoBean ChangePasswordUseCase changePasswordUseCase;
        @MockitoBean GetUserProfileUseCase getUserProfileUseCase;
        @MockitoBean RequestPasswordResetUseCase requestPasswordResetUseCase;
        @MockitoBean ResetPasswordUseCase resetPasswordUseCase;
        @MockitoBean RefreshRateLimiter refreshRateLimiter;
        @MockitoBean RequestOtpUseCase requestOtpUseCase;
        @MockitoBean VerifyOtpUseCase verifyOtpUseCase;
        @MockitoBean CreateCompanyUseCase createCompanyUseCase;
        @MockitoBean SocialSignInUseCase socialSignInUseCase;
        @MockitoBean JwtAuthFilter jwtAuthFilter;

        @BeforeEach
        void letFilterPassThrough() throws Exception {
            doAnswer(inv -> {
                FilterChain chain = inv.getArgument(2);
                chain.doFilter(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(jwtAuthFilter).doFilter(any(), any(), any());
            when(selectTenantUseCase.select(any(SelectTenantCommand.class))).thenReturn(new AuthTokens(
                    "access", "refresh", 86400L, UUID.randomUUID(), "kv_aaa111", "OWNER"));
            when(refreshTokenUseCase.refresh(anyString(), any(), anyBoolean())).thenReturn(new AuthTokens(
                    "access", "refresh", 86400L, UUID.randomUUID(), "kv_aaa111", "OWNER"));
        }

        static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
            return request.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        boolean selectLegacyFlag(MockHttpServletRequestBuilder request) throws Exception {
            mockMvc.perform(request).andExpect(status().isOk());
            ArgumentCaptor<SelectTenantCommand> captor = ArgumentCaptor.forClass(SelectTenantCommand.class);
            verify(selectTenantUseCase).select(captor.capture());
            return captor.getValue().legacyClient();
        }

        boolean refreshLegacyFlag(MockHttpServletRequestBuilder request) throws Exception {
            mockMvc.perform(request).andExpect(status().isOk());
            ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
            verify(refreshTokenUseCase).refresh(eq("refresh-tok"), any(), captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @WebMvcTest(AuthController.class)
    @Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
    @DisplayName("guard on (default)")
    class GuardOn extends Base {

        @Test
        @DisplayName("V1 mixed login (no header): only OWNER and EMPLOYEE memberships listed")
        void legacyLoginHidesManagerAndViewer() throws Exception {
            when(authenticateUserUseCase.authenticate(any(AuthenticateUserCommand.class))).thenReturn(MIXED);
            mockMvc.perform(json(post("/api/v1/auth/login"), LOGIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.loginToken").value("login-tok"))
                    .andExpect(jsonPath("$.memberships.length()").value(2))
                    .andExpect(jsonPath("$.memberships[0].tenantCode").value("KV-AAA111"))
                    .andExpect(jsonPath("$.memberships[0].role").value("OWNER"))
                    .andExpect(jsonPath("$.memberships[1].tenantCode").value("KV-DDD444"))
                    .andExpect(jsonPath("$.memberships[1].role").value("EMPLOYEE"));
        }

        @Test
        @DisplayName("V1 viewer login (no header, blank header): 200 with memberships []")
        void legacyViewerLoginGetsEmptyList() throws Exception {
            when(authenticateUserUseCase.authenticate(any(AuthenticateUserCommand.class))).thenReturn(VIEWER_ONLY);
            mockMvc.perform(json(post("/api/v1/auth/login"), LOGIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(0));
            mockMvc.perform(json(post("/api/v1/auth/login").header(AuthController.APP_VERSION_HEADER, "  "), LOGIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(0));
        }

        @Test
        @DisplayName("new app (X-App-Version): every membership listed, MANAGER/VIEWER with their real memberRole")
        void headeredLoginUnchanged() throws Exception {
            when(authenticateUserUseCase.authenticate(any(AuthenticateUserCommand.class))).thenReturn(MIXED);
            mockMvc.perform(json(post("/api/v1/auth/login").header(AuthController.APP_VERSION_HEADER, "2.0.0"), LOGIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(4))
                    .andExpect(jsonPath("$.memberships[1].memberRole").value("VIEWER"))
                    .andExpect(jsonPath("$.memberships[2].memberRole").value("MANAGER"));
        }

        @Test
        @DisplayName("otp/verify and social sign-in lists are filtered the same way")
        void otpAndSocialFiltered() throws Exception {
            when(verifyOtpUseCase.verify(any())).thenReturn(MIXED);
            when(socialSignInUseCase.signIn(any())).thenReturn(MIXED);
            mockMvc.perform(json(post("/api/v1/auth/otp/verify"), "{\"identifier\":\"awa@exemple.cm\",\"code\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(2));
            mockMvc.perform(json(post("/api/v1/auth/social/google"), "{\"idToken\":\"id-token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(2));
            mockMvc.perform(json(post("/api/v1/auth/social/apple"), "{\"identityToken\":\"id-token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(2));
            mockMvc.perform(json(post("/api/v1/auth/social/google").header(AuthController.APP_VERSION_HEADER, "1.0"),
                            "{\"idToken\":\"id-token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(4));
        }

        @Test
        @DisplayName("select-tenant: legacy flag set without the header")
        void selectTenantLegacyFlag() throws Exception {
            assertThat(selectLegacyFlag(json(post("/api/v1/auth/select-tenant"), SELECT))).isTrue();
        }

        @Test
        @DisplayName("select-tenant: legacy flag clear with the header")
        void selectTenantHeadered() throws Exception {
            assertThat(selectLegacyFlag(json(post("/api/v1/auth/select-tenant")
                    .header(AuthController.APP_VERSION_HEADER, "2.0.0+80"), SELECT))).isFalse();
        }

        @Test
        @DisplayName("refresh: legacy flag set without the header, 403 APP_UPDATE_REQUIRED passed through")
        void refreshLegacyFlag() throws Exception {
            assertThat(refreshLegacyFlag(json(post("/api/v1/auth/refresh"), REFRESH))).isTrue();

            when(refreshTokenUseCase.refresh(anyString(), any(), eq(true)))
                    .thenThrow(new DomainException(ErrorCode.APP_UPDATE_REQUIRED));
            mockMvc.perform(json(post("/api/v1/auth/refresh"), REFRESH))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.domainCode").value("APP_UPDATE_REQUIRED"))
                    .andExpect(jsonPath("$.error").value(
                            "Ce rôle nécessite la dernière version de l'application : mettez-la à jour"));
        }

        @Test
        @DisplayName("refresh: legacy flag clear with the header")
        void refreshHeadered() throws Exception {
            assertThat(refreshLegacyFlag(json(post("/api/v1/auth/refresh")
                    .header(AuthController.APP_VERSION_HEADER, "2.0.0"), REFRESH))).isFalse();
        }
    }

    @Nested
    @WebMvcTest(AuthController.class)
    @Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
    @TestPropertySource(properties = "keevo.app-version.legacy-role-guard.enabled=false")
    @DisplayName("guard off")
    class GuardOff extends Base {

        @Test
        @DisplayName("no header, VIEWER: listed, and no legacy flag at select-tenant / refresh")
        void unchangedBehaviour() throws Exception {
            when(authenticateUserUseCase.authenticate(any(AuthenticateUserCommand.class))).thenReturn(VIEWER_ONLY);
            mockMvc.perform(json(post("/api/v1/auth/login"), LOGIN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.memberships.length()").value(1))
                    .andExpect(jsonPath("$.memberships[0].memberRole").value("VIEWER"));
            assertThat(selectLegacyFlag(json(post("/api/v1/auth/select-tenant"), SELECT))).isFalse();
            assertThat(refreshLegacyFlag(json(post("/api/v1/auth/refresh"), REFRESH))).isFalse();
        }
    }
}
