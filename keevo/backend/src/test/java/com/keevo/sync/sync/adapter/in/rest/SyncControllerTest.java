package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncOperationDto;
import com.keevo.sync.sync.adapter.in.rest.dto.SyncPushRequestDto;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SyncControllerTest {

    @Mock private SyncUseCase syncUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;

    private MockMvc mockMvc;
    private ObjectMapper mapper;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        var controller = new SyncController(syncUseCase, jwtTokenProvider, conflictsLogRepository);
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

    private Claims claimsForRole(String role) {
        return Jwts.claims()
                .subject(actorId.toString())
                .add("role", role)
                .add("tenantId", "kv_abc123")
                .build();
    }

    private void mockJwt(String role) {
        authenticateAs(role);
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claimsForRole(role));
    }

    @Test
    void push_validBatch_owner_returns200() throws Exception {
        mockJwt("OWNER");
        var result = new SyncBatchResult(Instant.now(), List.of(
                new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null)));
        when(syncUseCase.pushBatch(any())).thenReturn(result);

        var body = Map.of(
                "deviceId", "device-1",
                "operations", List.of(Map.of(
                        "operationId", UUID.randomUUID().toString(),
                        "operationType", "CREATE_SALE",
                        "entityId", UUID.randomUUID().toString(),
                        "payload", Map.of("key", "value"),
                        "clientTimestamp", "2026-03-20T10:00:00Z")));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].status").value("APPLIED"));
    }

    @Test
    void push_validBatch_employee_returns200() throws Exception {
        mockJwt("EMPLOYEE");
        var result = new SyncBatchResult(Instant.now(), List.of(
                new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, null, null)));
        when(syncUseCase.pushBatch(any())).thenReturn(result);

        var body = Map.of(
                "deviceId", "device-1",
                "operations", List.of(Map.of(
                        "operationId", UUID.randomUUID().toString(),
                        "operationType", "CREATE_SALE",
                        "payload", Map.of(),
                        "clientTimestamp", "2026-03-20T10:00:00Z")));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void push_emptyOperations_returns400() throws Exception {
        authenticateAs("OWNER");

        var body = Map.of(
                "deviceId", "device-1",
                "operations", List.of());

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void push_missingDeviceId_returns400() throws Exception {
        authenticateAs("OWNER");

        var body = Map.of(
                "operations", List.of(Map.of(
                        "operationId", UUID.randomUUID().toString(),
                        "operationType", "CREATE_SALE",
                        "payload", Map.of(),
                        "clientTimestamp", "2026-03-20T10:00:00Z")));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void push_partialSuccess_returns200() throws Exception {
        mockJwt("OWNER");
        var result = new SyncBatchResult(Instant.now(), List.of(
                new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null),
                new SyncOperationResult("op-2", SyncOperationStatus.REJECTED, null, "UNKNOWN_OPERATION_TYPE")));
        when(syncUseCase.pushBatch(any())).thenReturn(result);

        var body = Map.of(
                "deviceId", "device-1",
                "operations", List.of(
                        Map.of("operationId", UUID.randomUUID().toString(),
                                "operationType", "CREATE_SALE",
                                "payload", Map.of(),
                                "clientTimestamp", "2026-03-20T10:00:00Z"),
                        Map.of("operationId", UUID.randomUUID().toString(),
                                "operationType", "UNKNOWN_TYPE",
                                "payload", Map.of(),
                                "clientTimestamp", "2026-03-20T10:00:00Z")));

        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer fake-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results.length()").value(2));
    }

    @Test
    void pull_withSince_returns200WithEntities() throws Exception {
        mockJwt("OWNER");
        var pullResult = new SyncPullResult(
                Instant.parse("2026-03-21T12:00:00Z"),
                Map.of("products", List.of(Map.<String, Object>of("id", "abc", "name", "Prod1"))),
                Map.of("products", 1));
        when(syncUseCase.pull(any())).thenReturn(pullResult);

        mockMvc.perform(get("/api/v1/sync/pull")
                        .param("since", "2026-03-20T10:00:00Z")
                        .header("Authorization", "Bearer fake-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTimestamp").exists())
                .andExpect(jsonPath("$.data.entities.products").isArray())
                .andExpect(jsonPath("$.data.entities.products.length()").value(1))
                .andExpect(jsonPath("$.data.counts.products").value(1));
    }

    @Test
    void pull_withoutSince_returns200FullSync() throws Exception {
        mockJwt("OWNER");
        var pullResult = new SyncPullResult(Instant.now(), Map.of(), Map.of());
        when(syncUseCase.pull(any())).thenReturn(pullResult);

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTimestamp").exists());
    }

    @Test
    void pull_employee_returns200() throws Exception {
        mockJwt("EMPLOYEE");
        var pullResult = new SyncPullResult(Instant.now(), Map.of(), Map.of());
        when(syncUseCase.pull(any())).thenReturn(pullResult);

        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer fake-jwt"))
                .andExpect(status().isOk());
    }
}
