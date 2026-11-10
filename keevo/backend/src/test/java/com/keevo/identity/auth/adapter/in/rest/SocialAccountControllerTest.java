package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkCommand;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkedSocialAccount;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story 19.3b — {@link SocialAccountController} (standalone MockMvc + GlobalExceptionHandler). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialAccountController (Story 19.3b)")
class SocialAccountControllerTest {

    private static final Instant LINKED = Instant.parse("2026-11-10T10:00:00Z");

    @Mock SocialAccountUseCase socialAccounts;

    private MockMvc mockMvc;
    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SocialAccountController(socialAccounts))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(me, null,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET → provider code, French label, date; never an id or subject")
    void list() throws Exception {
        when(socialAccounts.list(me)).thenReturn(List.of(new LinkedSocialAccount(SocialProvider.APPLE, LINKED)));

        mockMvc.perform(get("/api/v1/auth/social/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].provider").value("APPLE"))
                .andExpect(jsonPath("$.data[0].label").value("Apple"))
                .andExpect(jsonPath("$.data[0].linkedAt").exists())
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].subject").doesNotExist());
    }

    @Test
    @DisplayName("POST apple → command with token, code, nonce and the caller")
    void linkApple() throws Exception {
        when(socialAccounts.link(any())).thenReturn(List.of(new LinkedSocialAccount(SocialProvider.APPLE, LINKED)));

        mockMvc.perform(post("/api/v1/auth/social/accounts/apple").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\":\"tok\",\"authorizationCode\":\"code\","
                                + "\"nonce\":\"0123456789abcdef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].label").value("Apple"));

        ArgumentCaptor<LinkCommand> cmd = ArgumentCaptor.forClass(LinkCommand.class);
        verify(socialAccounts).link(cmd.capture());
        assertThat(cmd.getValue().userId()).isEqualTo(me);
        assertThat(cmd.getValue().provider()).isEqualTo(SocialProvider.APPLE);
        assertThat(cmd.getValue().token()).isEqualTo("tok");
        assertThat(cmd.getValue().authorizationCode()).isEqualTo("code");
        assertThat(cmd.getValue().nonce()).isEqualTo("0123456789abcdef");
        assertThat(cmd.getValue().clientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("POST google: blank token or 8-char nonce → 422, use case not called")
    void linkGoogleValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/social/accounts/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/auth/social/accounts/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"tok\",\"nonce\":\"short123\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(socialAccounts, never()).link(any());
    }

    @Test
    @DisplayName("POST google linked elsewhere → 409 SOCIAL_ACCOUNT_LINKED_ELSEWHERE with its French message")
    void linkConflict() throws Exception {
        when(socialAccounts.link(any())).thenThrow(new DomainException(ErrorCode.SOCIAL_ACCOUNT_LINKED_ELSEWHERE));

        mockMvc.perform(post("/api/v1/auth/social/accounts/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"tok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("SOCIAL_ACCOUNT_LINKED_ELSEWHERE"))
                .andExpect(jsonPath("$.error").value("Ce compte Google ou Apple est déjà lié à un autre compte Keevo"));
    }

    @Test
    @DisplayName("DELETE google|APPLE → 204; last method → 409; unknown provider → 404")
    void unlink() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/social/accounts/google")).andExpect(status().isNoContent());
        verify(socialAccounts).unlink(me, SocialProvider.GOOGLE);

        doThrow(new DomainException(ErrorCode.LAST_SIGN_IN_METHOD)).when(socialAccounts)
                .unlink(me, SocialProvider.APPLE);
        mockMvc.perform(delete("/api/v1/auth/social/accounts/APPLE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Impossible de retirer votre seul moyen de connexion"));

        mockMvc.perform(delete("/api/v1/auth/social/accounts/facebook")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST google, provider already linked → 409 SOCIAL_PROVIDER_ALREADY_LINKED; blank nonce accepted as absent")
    void providerAlreadyLinked() throws Exception {
        when(socialAccounts.link(any())).thenThrow(new DomainException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED));

        mockMvc.perform(post("/api/v1/auth/social/accounts/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"tok\",\"nonce\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("SOCIAL_PROVIDER_ALREADY_LINKED"))
                .andExpect(jsonPath("$.error").value("Un compte de ce fournisseur est déjà lié. Retirez-le d'abord"));
    }
}
