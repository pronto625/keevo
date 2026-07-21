package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushRequestDto;
import com.keevo.sync.sync.application.service.SyncGateCheckService;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SyncControllerPushGateTest {

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

    private Map<String, Object> validPushBody(String deviceId) {
        return Map.of(
                "deviceId", deviceId,
                "operations", List.of(Map.of(
                        "operationId", UUID.randomUUID().toString(),
                        "operationType", "CREATE_SALE",
                        "entityId", UUID.randomUUID().toString(),
                        "payload", Map.of("key", "value"),
                        "clientTimestamp", "2026-03-20T10:00:00Z")));
    }

    // ── Gate: fresh device (not stale) ───────────────────────────────────────

    @Test
    void push_freshDevice_returns200() throws Exception {
        mockJwt("OWNER");
        when(syncGateCheckService.isStalePush("device-fresh")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-fresh"))))
                .andExpect(status().isOk());
    }

    @Test
    void push_unknownDevice_returns200() throws Exception {
        mockJwt("OWNER");
        // Device not found in DB → isStalePush returns false → first push allowed
        when(syncGateCheckService.isStalePush("device-new")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-new"))))
                .andExpect(status().isOk());
    }

    // ── Gate: stale device (>7 days) ─────────────────────────────────────────

    @Test
    void push_staleDevice_returns423() throws Exception {
        mockJwt("OWNER");
        Instant lastPushAt = Instant.now().minus(8, ChronoUnit.DAYS);
        when(syncGateCheckService.isStalePush("device-stale")).thenReturn(true);
        when(userSyncStateRepository.findByDeviceId("device-stale"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-stale", actorId, "kv_abc123",
                        lastPushAt, null, Instant.now())));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-stale"))))
                .andExpect(status().isLocked());
    }

    @Test
    void push_staleDevice_hasDomainCode() throws Exception {
        mockJwt("OWNER");
        Instant lastPushAt = Instant.now().minus(8, ChronoUnit.DAYS);
        when(syncGateCheckService.isStalePush("device-stale")).thenReturn(true);
        when(userSyncStateRepository.findByDeviceId("device-stale"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-stale", actorId, "kv_abc123",
                        lastPushAt, null, Instant.now())));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-stale"))))
                .andExpect(jsonPath("$.domainCode").value("SYNC_REQUIRED"))
                .andExpect(jsonPath("$.code").value("SYNC_REQUIRED"));
    }

    @Test
    void push_staleDevice_hasDaysSinceLastSync() throws Exception {
        mockJwt("OWNER");
        Instant lastPushAt = Instant.now().minus(8, ChronoUnit.DAYS);
        when(syncGateCheckService.isStalePush("device-stale")).thenReturn(true);
        when(userSyncStateRepository.findByDeviceId("device-stale"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-stale", actorId, "kv_abc123",
                        lastPushAt, null, Instant.now())));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-stale"))))
                .andExpect(jsonPath("$.details.daysSinceLastSync").value(greaterThanOrEqualTo(8)));
    }

    @Test
    void push_staleDevice_hasLastPushAt() throws Exception {
        mockJwt("OWNER");
        Instant lastPushAt = Instant.now().minus(8, ChronoUnit.DAYS);
        when(syncGateCheckService.isStalePush("device-stale")).thenReturn(true);
        when(userSyncStateRepository.findByDeviceId("device-stale"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-stale", actorId, "kv_abc123",
                        lastPushAt, null, Instant.now())));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-stale"))))
                .andExpect(jsonPath("$.details.lastPushAt").isNotEmpty());
    }

    // ── Gate: empty operations batch (device state check) ────────────────────

    @Test
    void push_emptyOperations_freshDevice_returns200() throws Exception {
        mockJwt("OWNER");
        when(syncGateCheckService.isStalePush("device-1")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        var body = Map.of("deviceId", "device-1", "operations", List.of());

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    // ── AC2: deviceId ownership check (v1s-13-2) ─────────────────────────────

    @Test
    void shouldRejectDeviceIdNotOwnedByActor() throws Exception {
        mockJwt("OWNER");
        UUID otherUserId = UUID.randomUUID();

        // Device exists but belongs to a different user
        when(userSyncStateRepository.findByDeviceId("device-A"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-A", otherUserId, "kv_abc123",
                        Instant.now(), null, Instant.now())));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-A"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("DEVICE_ID_MISMATCH"));
    }

    @Test
    void shouldAllowDeviceIdWhenNotYetRegistered() throws Exception {
        mockJwt("OWNER");
        // Device not found in DB → first push is allowed (backward-compatible)
        when(userSyncStateRepository.findByDeviceId("device-new")).thenReturn(Optional.empty());
        when(syncGateCheckService.isStalePush("device-new")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-new"))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowDeviceIdWhenOwnedByActor() throws Exception {
        mockJwt("OWNER");
        // Device exists and belongs to the current user
        when(userSyncStateRepository.findByDeviceId("device-own"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-own", actorId, "kv_abc123",
                        Instant.now(), null, Instant.now())));
        when(syncGateCheckService.isStalePush("device-own")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-own"))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowDeviceIdWhenUserIdNull() throws Exception {
        mockJwt("OWNER");
        // Device pre-5.4 with user_id = null → tolerated (migration progressive)
        when(userSyncStateRepository.findByDeviceId("device-legacy"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-legacy", null, "kv_abc123",
                        Instant.now(), null, Instant.now())));
        when(syncGateCheckService.isStalePush("device-legacy")).thenReturn(false);
        when(syncUseCase.pushBatch(any())).thenReturn(
                new SyncBatchResult(Instant.now(), List.of()));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validPushBody("device-legacy"))))
                .andExpect(status().isOk());
    }
}
