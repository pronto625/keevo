package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.domain.port.in.WebPushSubscriptionUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story 19.9b — Web Push endpoints behind the real security chain. */
@WebMvcTest(WebPushController.class)
@Import(SecurityConfig.class)
@DisplayName("WebPushController (Story 19.9b)")
class WebPushControllerTest {

    private static final String FCM = "https://fcm.googleapis.com/fcm/send/abc";
    private static final String SUBSCRIBE_BODY =
            "{\"endpoint\":\"" + FCM + "\",\"p256dh\":\"BKey\",\"auth\":\"secret\",\"userAgent\":\"Firefox\"}";

    @Autowired MockMvc mockMvc;

    @MockitoBean WebPushSubscriptionUseCase useCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("anonymous GET /vapid-public-key → 200 {publicKey}")
    void publicKey() throws Exception {
        filterAs(null);
        when(useCase.vapidPublicKey()).thenReturn("BPublic");

        mockMvc.perform(get("/api/v1/web-push/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value("BPublic"));
    }

    @Test
    @DisplayName("anonymous POST /subscribe → 401, nothing stored")
    void anonymousSubscribe() throws Exception {
        filterAs(null);

        mockMvc.perform(post("/api/v1/web-push/subscribe").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIBE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("POST /subscribe → 204 for the caller")
    void subscribe() throws Exception {
        UUID user = UUID.randomUUID();
        filterAs(user);

        mockMvc.perform(post("/api/v1/web-push/subscribe").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIBE_BODY))
                .andExpect(status().isNoContent());

        verify(useCase).subscribe(user, FCM, "BKey", "secret", "Firefox");
    }

    @Test
    @DisplayName("POST /subscribe, endpoint of another account → 409 PUSH_ENDPOINT_OWNED")
    void owned() throws Exception {
        filterAs(UUID.randomUUID());
        doThrow(new DomainException(ErrorCode.PUSH_ENDPOINT_OWNED))
                .when(useCase).subscribe(any(), anyString(), anyString(), anyString(), any());

        mockMvc.perform(post("/api/v1/web-push/subscribe").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIBE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("PUSH_ENDPOINT_OWNED"));
    }

    @Test
    @DisplayName("POST /subscribe, invalid subscription → 422 VALIDATION_FAILED")
    void invalid() throws Exception {
        filterAs(UUID.randomUUID());
        doThrow(new DomainException(ErrorCode.VALIDATION_FAILED))
                .when(useCase).subscribe(any(), anyString(), anyString(), anyString(), any());

        mockMvc.perform(post("/api/v1/web-push/subscribe").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBSCRIBE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("DELETE /subscribe → 204, the caller's row only")
    void unsubscribe() throws Exception {
        UUID user = UUID.randomUUID();
        filterAs(user);

        mockMvc.perform(delete("/api/v1/web-push/subscribe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + FCM + "\"}"))
                .andExpect(status().isNoContent());

        verify(useCase).unsubscribe(user, FCM);
    }

    /** {@code actorId} null = anonymous. */
    private void filterAs(UUID actorId) throws Exception {
        doAnswer(inv -> {
            if (actorId != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        actorId, null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
            }
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
