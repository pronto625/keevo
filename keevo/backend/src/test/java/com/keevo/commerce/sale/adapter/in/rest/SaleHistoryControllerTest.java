package com.keevo.commerce.sale.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase.SalesHistoryQuery;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TDD RED tests for SaleHistoryController.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
@ExtendWith(MockitoExtension.class)
class SaleHistoryControllerTest {

    @Mock private GetSalesHistoryUseCase getSalesHistoryUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new SaleHistoryController(getSalesHistoryUseCase, jwtTokenProvider);
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
        when(jwtTokenProvider.extractRole(any(Claims.class))).thenReturn(role);
    }

    private Sale createSale() {
        UUID saleId = UUID.randomUUID();
        var item = new SaleItem(UUID.randomUUID(), saleId, UUID.randomUUID(), null,
                "Produit A", 1500, 1500, 2);
        return new Sale(saleId, storeId, actorId, null,
                PaymentMode.CASH, 3000, 0, SaleStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of(item));
    }

    // ── GET /api/v1/sales/history ─────────────────────────────────────────────

    @Test
    void GET_salesHistory_returns200_forOwner() throws Exception {
        mockJwt("OWNER");

        var sale = createSale();
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(sale)));

        mockMvc.perform(get("/api/v1/sales/history")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("from", "2026-03-19")
                        .param("to", "2026-03-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(3000))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"));

        verify(getSalesHistoryUseCase).getSalesHistory(any(SalesHistoryQuery.class));
    }

    @Test
    void GET_salesHistory_returns200_forEmployee_onlyOwnSales() throws Exception {
        mockJwt("EMPLOYEE");

        var sale = createSale();
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(sale)));

        mockMvc.perform(get("/api/v1/sales/history")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("from", "2026-03-19")
                        .param("to", "2026-03-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        // Verify that the service was called with EMPLOYEE role
        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query ->
                "EMPLOYEE".equals(query.role())));
    }

    @Test
    void GET_salesHistory_returns400_withoutStoreId() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get("/api/v1/sales/history")
                        .header("Authorization", "Bearer fake-token")
                        .param("from", "2026-03-19")
                        .param("to", "2026-03-19"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_salesHistory_usesPaginationParams() throws Exception {
        mockJwt("OWNER");

        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 25), 100));

        mockMvc.perform(get("/api/v1/sales/history")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("from", "2026-03-19")
                        .param("to", "2026-03-19")
                        .param("page", "1")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(25));

        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query ->
                query.page() == 1 && query.size() == 25));
    }

    @Test
    void GET_salesHistory_includesSaleItems() throws Exception {
        mockJwt("OWNER");

        var sale = createSale();
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(sale)));

        mockMvc.perform(get("/api/v1/sales/history")
                        .header("Authorization", "Bearer fake-token")
                        .param("storeId", storeId.toString())
                        .param("from", "2026-03-19")
                        .param("to", "2026-03-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].items").isArray())
                .andExpect(jsonPath("$.data.content[0].items[0].productName").value("Produit A"))
                .andExpect(jsonPath("$.data.content[0].items[0].quantity").value(2));
    }
}
