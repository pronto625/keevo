package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.sync.sync.application.service.SyncGateCheckService;
import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SyncControllerDevicesTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock private SyncUseCase syncUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private SyncGateCheckService syncGateCheckService;
    @Mock private UserSyncStateRepository userSyncStateRepository;

    private MockMvc mockMvc;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var controller = new SyncController(syncUseCase, jwtTokenProvider, conflictsLogRepository,
                syncGateCheckService, userSyncStateRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        actorId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void mockJwt(String role) {
        authenticateAs(role);
        Claims claims = Jwts.claims()
                .subject(actorId.toString())
                .add("role", role)
                .add("tenantId", "kv_abc123")
                .build();
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claims);
    }

    // J24 (device test): 18 « appareils actifs » for one phone — every account
    // switch renews the device id. Only devices seen within 30 days count.
    @Test
    void getDevices_listsOnlyDevicesSeenInTheLast30Days() throws Exception {
        mockJwt("OWNER");
        Instant old = Instant.now().minus(java.time.Duration.ofDays(45));
        var stale = new UserSyncState("device-old", actorId, "kv_abc123", old, old, old);
        var recentPull = new UserSyncState("device-pull", actorId, "kv_abc123",
                old, Instant.now().minus(java.time.Duration.ofDays(2)), old);
        var current = new UserSyncState("device-now", actorId, "kv_abc123",
                null, null, Instant.now());
        when(userSyncStateRepository.findAllByTenantId("kv_abc123"))
                .thenReturn(List.of(stale, recentPull, current));

        mockMvc.perform(get("/api/v1/sync/devices")
                        .header("Authorization", "Bearer fake-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deviceId").value("device-pull"))
                .andExpect(jsonPath("$.data[1].deviceId").value("device-now"));
    }

    @Test
    void getDevices_authenticated_returns200WithList() throws Exception {
        mockJwt("OWNER");
        var device = new UserSyncState(
                "device-abc", actorId, "kv_abc123",
                Instant.parse("2026-03-24T10:30:00Z"), null, Instant.now());
        when(userSyncStateRepository.findAllByTenantId("kv_abc123"))
                .thenReturn(List.of(device));

        mockMvc.perform(get("/api/v1/sync/devices")
                        .header("Authorization", "Bearer fake-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].deviceId").value("device-abc"))
                .andExpect(jsonPath("$.data[0].userId").value(actorId.toString()))
                .andExpect(jsonPath("$.data[0].lastPushAt").exists());
    }

    @Test
    void getDevices_noSecurityContext_returns500_standaloneSetupLimitation() throws Exception {
        // standaloneSetup does not wire Spring Security filters, so missing
        // Authentication causes extractClaims() to throw IllegalArgumentException → 500.
        // In production, JwtAuthFilter returns 401 before the controller is reached.
        // This test validates the controller's guard logic, not the security filter.
        mockMvc.perform(get("/api/v1/sync/devices"))
                .andExpect(status().is5xxServerError());
    }
}
