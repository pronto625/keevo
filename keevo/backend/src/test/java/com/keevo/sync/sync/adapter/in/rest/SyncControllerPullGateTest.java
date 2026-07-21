package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.sync.sync.application.service.SyncGateCheckService;
import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the deviceId ownership check on the pull endpoint (v1s-13-2 AC2).
 * Mirrors SyncControllerPushGateTest but targets GET /api/v1/sync/pull with X-Device-Id header.
 */
@ExtendWith(MockitoExtension.class)
class SyncControllerPullGateTest {

    @Mock private SyncUseCase syncUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private SyncGateCheckService syncGateCheckService;
    @Mock private UserSyncStateRepository userSyncStateRepository;

    private MockMvc mockMvc;
    private ObjectMapper mapper;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var controller = new SyncController(
                syncUseCase, jwtTokenProvider, conflictsLogRepository,
                syncGateCheckService, userSyncStateRepository);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        actorId = UUID.randomUUID();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void mockJwt(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(
                Jwts.claims()
                        .subject(actorId.toString())
                        .add("role", role)
                        .add("tenantId", "kv_abc123")
                        .build());
    }

    // ── AC2: deviceId ownership check on pull endpoint (v1s-13-2) ────────────

    @Test
    void shouldRejectPullWhenDeviceIdNotOwnedByActor() throws Exception {
        mockJwt("OWNER");
        UUID otherUserId = UUID.randomUUID();

        // Device exists but belongs to a different user
        when(userSyncStateRepository.findByDeviceId("device-A"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-A", otherUserId, "kv_abc123",
                        Instant.now(), null, Instant.now())));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .header("X-Device-Id", "device-A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("DEVICE_ID_MISMATCH"));
    }

    @Test
    void shouldAllowPullWhenDeviceIdNotYetRegistered() throws Exception {
        mockJwt("OWNER");
        // Device not found in DB → first pull is allowed (backward-compatible)
        when(userSyncStateRepository.findByDeviceId("device-new")).thenReturn(Optional.empty());
        when(syncUseCase.pull(any())).thenReturn(
                new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .header("X-Device-Id", "device-new")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowPullWhenDeviceIdOwnedByActor() throws Exception {
        mockJwt("OWNER");
        // Device exists and belongs to the current user
        when(userSyncStateRepository.findByDeviceId("device-own"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-own", actorId, "kv_abc123",
                        Instant.now(), null, Instant.now())));
        when(syncUseCase.pull(any())).thenReturn(
                new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .header("X-Device-Id", "device-own")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowPullWhenDeviceIdUserIdNull() throws Exception {
        mockJwt("OWNER");
        // Device pre-5.4 with user_id = null → tolerated (migration progressive)
        when(userSyncStateRepository.findByDeviceId("device-legacy"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-legacy", null, "kv_abc123",
                        Instant.now(), null, Instant.now())));
        when(syncUseCase.pull(any())).thenReturn(
                new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .header("X-Device-Id", "device-legacy")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowPullWhenNoDeviceIdHeader() throws Exception {
        mockJwt("OWNER");
        // No X-Device-Id header → no ownership check (header is optional)
        when(syncUseCase.pull(any())).thenReturn(
                new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
