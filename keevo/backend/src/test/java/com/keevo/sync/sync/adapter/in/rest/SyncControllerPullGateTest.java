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
import org.junit.jupiter.api.AfterEach;
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

    // ── Story v1s-18-7 — role-aware pull (owner-only deltas) ─────────────────

    @Test
    void ownerPull_passesOwnerFlagTrue() throws Exception {
        mockJwt("OWNER");
        when(syncUseCase.pull(any())).thenReturn(new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(SyncUseCase.PullCommand.class);
        org.mockito.Mockito.verify(syncUseCase).pull(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().owner()).isTrue();
    }

    @Test
    void employeePull_passesOwnerFlagFalse() throws Exception {
        mockJwt("EMPLOYEE");
        when(syncUseCase.pull(any())).thenReturn(new SyncPullResult(Instant.now(), Map.of(), Map.of()));

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(SyncUseCase.PullCommand.class);
        org.mockito.Mockito.verify(syncUseCase).pull(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().owner()).isFalse();
    }

    // ── Story 12.12 — security log helpers ───────────────────────────────────

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureSecurityLog() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("SECURITY");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void releaseSecurityLog(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("SECURITY")).detachAppender(appender);
    }

    @Test
    void mismatchOnPullEmitsSecurityWarnAndNeverUpserts() throws Exception {
        mockJwt("EMPLOYEE");
        UUID ownerId = UUID.randomUUID();
        when(userSyncStateRepository.findByDeviceId("device-A"))
                .thenReturn(Optional.of(new UserSyncState(
                        "device-A", ownerId, "kv_abc123", Instant.now(), null, Instant.now())));
        var appender = captureSecurityLog();
        try {
            mockMvc.perform(get("/api/v1/sync/pull")
                            .header("Authorization", "Bearer fake-jwt")
                            .header("X-Device-Id", "device-A")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());

            org.assertj.core.api.Assertions.assertThat(appender.list).hasSize(1);
            var event = appender.list.get(0);
            org.assertj.core.api.Assertions.assertThat(event.getLevel())
                    .isEqualTo(ch.qos.logback.classic.Level.WARN);
            org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                    .contains("DEVICE_ID_MISMATCH", "actorId=" + actorId, "ownerId=" + ownerId,
                            "tenantId=kv_abc123", "deviceId=device-A", "/api/v1/sync/pull");
        } finally {
            releaseSecurityLog(appender);
        }
        org.mockito.Mockito.verify(userSyncStateRepository, org.mockito.Mockito.never()).upsertOnPull(any());
    }

    @Test
    void guardedUpsertWritingNothingLogsWarnButStillServesPull() throws Exception {
        mockJwt("OWNER");
        // Race: device unknown at check time, claimed by another user before the upsert.
        when(userSyncStateRepository.findByDeviceId("device-race")).thenReturn(Optional.empty());
        when(userSyncStateRepository.upsertOnPull(any())).thenReturn(false);
        when(syncUseCase.pull(any())).thenReturn(new SyncPullResult(Instant.now(), Map.of(), Map.of()));
        var appender = captureSecurityLog();
        try {
            mockMvc.perform(get("/api/v1/sync/pull")
                            .header("Authorization", "Bearer fake-jwt")
                            .header("X-Device-Id", "device-race")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .anySatisfy(e -> org.assertj.core.api.Assertions.assertThat(e.getFormattedMessage())
                            .contains("DEVICE_OWNERSHIP_RACE", "deviceId=device-race"));
        } finally {
            releaseSecurityLog(appender);
        }
    }

    @Test
    void securityLogSanitisesClientDeviceId() {
        org.assertj.core.api.Assertions.assertThat(SyncController.sanitizeForLog("dev\r\nFAKE LOG LINE"))
                .isEqualTo("dev__FAKE LOG LINE");
        org.assertj.core.api.Assertions.assertThat(SyncController.sanitizeForLog("x".repeat(200)))
                .hasSize(65);
        org.assertj.core.api.Assertions.assertThat(SyncController.sanitizeForLog(null)).isNull();
    }
}
