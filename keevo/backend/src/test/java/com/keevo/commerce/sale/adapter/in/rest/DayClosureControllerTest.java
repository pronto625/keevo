package com.keevo.commerce.sale.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TDD RED tests for DayClosureController.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@ExtendWith(MockitoExtension.class)
class DayClosureControllerTest {

    @Mock private CloseDayUseCase closeDayUseCase;
    @Mock private DayClosureRepository dayClosureRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new DayClosureController(closeDayUseCase, dayClosureRepository, jwtTokenProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Claims claimsWithStore(String role) {
        return Jwts.claims()
                .subject(actorId.toString())
                .add("role", role)
                .add("storeId", storeId.toString())
                .add("tenantId", "kv_abc123")
                .build();
    }

    private void mockJwt(String role) {
        authenticateAs(role);
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claimsWithStore(role));
        when(jwtTokenProvider.extractTenantId(any(Claims.class))).thenReturn("kv_abc123");
    }

    // ── POST /api/v1/day-closures ─────────────────────────────────────────────

    @Test
    void POST_dayClosures_returns201_forOwner() throws Exception {
        mockJwt("OWNER");

        var summary = new DayClosureSummary(5, 50000, UUID.randomUUID().toString(),
                "iPhone 14", 3, 30000, 20000, 2, 10000);
        var closure = new DayClosure(UUID.randomUUID(), storeId, actorId, Instant.now(), summary, false, "kv_abc123");
        when(closeDayUseCase.closeDay(any(CloseDayCommand.class))).thenReturn(closure);

        mockMvc.perform(post("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("storeId", storeId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary.totalSales").value(5))
                .andExpect(jsonPath("$.data.summary.totalRevenue").value(50000))
                .andExpect(jsonPath("$.data.summary.topProductName").value("iPhone 14"));

        verify(closeDayUseCase).closeDay(any(CloseDayCommand.class));
    }

    @Test
    void POST_dayClosures_returns201_forEmployee() throws Exception {
        mockJwt("EMPLOYEE");

        var summary = new DayClosureSummary(3, 25000, null, null, 0, 25000, 0, 0, 0);
        var closure = new DayClosure(UUID.randomUUID(), storeId, actorId, Instant.now(), summary, false, "kv_abc123");
        when(closeDayUseCase.closeDay(any(CloseDayCommand.class))).thenReturn(closure);

        mockMvc.perform(post("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("storeId", storeId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.summary.totalSales").value(3));

        verify(closeDayUseCase).closeDay(any(CloseDayCommand.class));
    }

    @Test
    void POST_dayClosures_returns409_whenAlreadyClosed() throws Exception {
        mockJwt("OWNER");

        when(closeDayUseCase.closeDay(any(CloseDayCommand.class)))
                .thenThrow(new DomainException(ErrorCode.DAY_ALREADY_CLOSED,
                        "La journée a déjà été clôturée pour cette boutique"));

        mockMvc.perform(post("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("storeId", storeId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("DAY_ALREADY_CLOSED"));
    }

    // ── GET /api/v1/day-closures ──────────────────────────────────────────────

    @Test
    void GET_dayClosures_returns200_whenClosureExists() throws Exception {
        mockJwt("OWNER");
        LocalDate date = LocalDate.of(2026, 3, 19);
        
        DayClosureSummary summary = new DayClosureSummary(
            3,           // totalSales
            5000,        // totalRevenue (XAF)
            "prod-123",  // topProductId
            "Baguette",  // topProductName
            15,          // topProductQty
            3000,        // cashAmount
            2000,        // momoAmount
            0,           // pendingSalesCount
            0            // pendingSalesTotal
        );
        
        DayClosure closure = new DayClosure(
            UUID.randomUUID(),
            storeId,
            actorId,
            Instant.now(),
            summary,
            false,  // isAutomatic
            "kv_test"
        );
        
        when(dayClosureRepository.findByStoreIdAndDate(storeId, date))
            .thenReturn(List.of(closure));

        mockMvc.perform(get("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("date", "2026-03-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(storeId.toString()))
                .andExpect(jsonPath("$.data.isAutomatic").value(false))
                .andExpect(jsonPath("$.data.summary.totalSales").value(3))
                .andExpect(jsonPath("$.data.summary.totalRevenue").value(5000));
    }

    @Test
    void GET_dayClosures_returns404_whenNoClosureFound() throws Exception {
        mockJwt("OWNER");
        LocalDate date = LocalDate.of(2026, 3, 19);
        
        when(dayClosureRepository.findByStoreIdAndDate(storeId, date))
            .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("date", "2026-03-19"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("CLOSURE_NOT_FOUND"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void POST_dayClosures_returns422_whenMissingStoreId() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(post("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Story 12.6 — GET /day-closures OWNER-only ─────────────────────

    @Test
    void GET_dayClosures_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(get("/api/v1/day-closures")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("date", "2026-03-19"))
                .andExpect(status().isForbidden());
    }
}
