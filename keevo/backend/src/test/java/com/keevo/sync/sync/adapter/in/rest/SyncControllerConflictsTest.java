package com.keevo.sync.sync.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.sync.sync.domain.model.SyncConflictsLogEntry;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SyncControllerConflictsTest {

    @Mock private SyncUseCase syncUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;

    private MockMvc mockMvc;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        var mapper = new ObjectMapper();
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

    private void mockJwt(String role) {
        authenticateAs(role);
        Claims claims = Jwts.claims()
                .subject(actorId.toString())
                .add("role", role)
                .add("tenantId", "kv_abc123")
                .build();
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claims);
    }

    @Test
    void getConflicts_owner_returns200() throws Exception {
        authenticateAs("OWNER");
        var entry = new SyncConflictsLogEntry(
                "id-1", "op-1", "CREATE_SALE", "p1", "stock_level",
                "STOCK_NEGATIVE", "DELTA_SUM",
                Map.of("resultingStock", -1), Instant.now(), actorId.toString());
        when(conflictsLogRepository.findAll(50, 0)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/sync/conflicts")
                        .param("limit", "50")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conflictType").value("STOCK_NEGATIVE"))
                .andExpect(jsonPath("$.data[0].strategy").value("DELTA_SUM"));
    }

    @Test
    void getConflicts_withLimitAndOffset_passesParams() throws Exception {
        authenticateAs("OWNER");
        when(conflictsLogRepository.findAll(10, 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sync/conflicts")
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getConflicts_returnsConflictEntriesWithConflictData() throws Exception {
        authenticateAs("OWNER");
        var data = Map.<String, Object>of("productId", "p1", "resultingStock", -2);
        var entry = new SyncConflictsLogEntry(
                "id-1", "op-1", "CREATE_SALE", "p1", "stock_level",
                "STOCK_NEGATIVE", "DELTA_SUM", data, Instant.now(), actorId.toString());
        when(conflictsLogRepository.findAll(50, 0)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conflictData.productId").value("p1"))
                .andExpect(jsonPath("$.data[0].conflictData.resultingStock").value(-2));
    }

    @Test
    void getConflicts_emptyList_returns200WithEmptyArray() throws Exception {
        authenticateAs("OWNER");
        when(conflictsLogRepository.findAll(50, 0)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getConflicts_endpointHasOwnerOnlyPreAuthorize() throws Exception {
        var method = SyncController.class.getMethod("getConflicts", int.class, int.class);
        var annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('OWNER')");
    }

    @Test
    void getConflicts_responseIncludesEntityTypeAndActorId() throws Exception {
        authenticateAs("OWNER");
        var entry = new SyncConflictsLogEntry(
                "id-1", "op-1", "CREATE_SALE", "p1", "stock_level",
                "STOCK_NEGATIVE", "DELTA_SUM",
                Map.of("resultingStock", -1), Instant.now(), actorId.toString());
        when(conflictsLogRepository.findAll(50, 0)).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/sync/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entityType").value("stock_level"))
                .andExpect(jsonPath("$.data[0].actorId").value(actorId.toString()));
    }
}
