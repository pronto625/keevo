package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.domain.port.in.DeleteDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenCommand;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenUseCase;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DeviceTokenControllerSecurityTest — v1s-15-7 CurrentActor follow-up (v1s-15-9 review).
 *
 * <p>Runs {@link DeviceTokenController} behind the real {@link SecurityConfig} filter chain:
 * anonymous callers are rejected before {@code CurrentActor.actorId()} is reached.
 */
@WebMvcTest(DeviceTokenController.class)
@Import(SecurityConfig.class)
@DisplayName("DeviceTokenController — security slice")
class DeviceTokenControllerSecurityTest {

    private static final String REGISTER_BODY =
            "{\"token\":\"fcm-tok\",\"platform\":\"ANDROID\",\"deviceName\":\"Test\"}";
    private static final String DELETE_BODY = "{\"token\":\"fcm-tok\"}";

    @Autowired MockMvc mockMvc;

    @MockitoBean RegisterDeviceTokenUseCase registerUseCase;
    @MockitoBean DeleteDeviceTokenUseCase deleteUseCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("anonymous POST /api/v1/devices/token → 401, nothing registered")
    void anonymous_post_returns401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(registerUseCase);
    }

    @Test
    @DisplayName("anonymous DELETE /api/v1/devices/token → 401, nothing deleted")
    void anonymous_delete_returns401() throws Exception {
        filterAsAnonymous();

        mockMvc.perform(delete("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON).content(DELETE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(deleteUseCase);
    }

    @Test
    @DisplayName("OWNER POST /api/v1/devices/token → 200, registered for the actor with role OWNER")
    void owner_post_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        filterAs(actorId, "ROLE_OWNER");

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registered").value(true));

        ArgumentCaptor<RegisterDeviceTokenCommand> captor =
                ArgumentCaptor.forClass(RegisterDeviceTokenCommand.class);
        verify(registerUseCase).register(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("authenticated without authority POST /api/v1/devices/token → 200, role falls back to OWNER")
    void noAuthority_post_fallsBackToOwnerRole() throws Exception {
        UUID actorId = UUID.randomUUID();
        filterAs(actorId, null);

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<RegisterDeviceTokenCommand> captor =
                ArgumentCaptor.forClass(RegisterDeviceTokenCommand.class);
        verify(registerUseCase).register(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("OWNER DELETE /api/v1/devices/token → 200, token deleted for the actor")
    void owner_delete_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        filterAs(actorId, "ROLE_OWNER");
        when(deleteUseCase.delete(actorId, "fcm-tok")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON).content(DELETE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        verify(deleteUseCase).delete(actorId, "fcm-tok");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void filterAs(UUID actorId, String authority) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    actorId, null, authority == null
                            ? List.<SimpleGrantedAuthority>of()
                            : List.of(new SimpleGrantedAuthority(authority)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private void filterAsAnonymous() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
