package com.keevo.commerce.sale.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.shared.event.catalog.sale.*;
import com.keevo.commerce.api.*;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase;
import com.keevo.commerce.sale.domain.port.in.GetSalesHistoryUseCase.SalesHistoryQuery;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock private GetSalesHistoryUseCase getSalesHistoryUseCase;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new SaleHistoryController(getSalesHistoryUseCase,
                new com.keevo.commerce.sale.adapter.in.SalesReadScope(
                        com.keevo.shared.infrastructure.security.ShippedActorPermissions.get()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        // Story 19.5b — a store member carries his store, as JwtAuthFilter sets it
        com.keevo.shared.infrastructure.security.ShippedActorPermissions.authenticate(
                actorId, role, "OWNER".equals(role) ? null : storeId);
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

        // Story 19.5b — an EMPLOYEE's history is forced to his own sales
        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query ->
                actorId.equals(query.employeeId()) && storeId.equals(query.storeId())));
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

    @Test
    void GET_salesHistory_manager_seesHisWholeStore_withOptionalEmployeeFilter() throws Exception {
        // Story 19.5b — STORE_SALES_VIEW: every sale of his store; an employee filter is honoured
        mockJwt("MANAGER");
        UUID seller = UUID.randomUUID();
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(createSale())));

        mockMvc.perform(get("/api/v1/sales/history").param("storeId", storeId.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sales/history").param("storeId", storeId.toString())
                        .param("employeeId", seller.toString()))
                .andExpect(status().isOk());

        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query ->
                storeId.equals(query.storeId()) && query.employeeId() == null));
        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query -> seller.equals(query.employeeId())));
    }

    @Test
    void GET_salesHistory_viewer_seesHisWholeStore() throws Exception {
        // Story 19.5b (DD1) — a VIEWER reads his store's sales (he never sells: his own history would be empty)
        mockJwt("VIEWER");
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of(createSale())));

        mockMvc.perform(get("/api/v1/sales/history").param("storeId", storeId.toString()))
                .andExpect(status().isOk());

        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query -> query.employeeId() == null));
    }

    @Test
    void GET_salesHistory_employee_filterOnAnotherSeller_isForcedToHimself() throws Exception {
        mockJwt("EMPLOYEE");
        when(getSalesHistoryUseCase.getSalesHistory(any(SalesHistoryQuery.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/sales/history").param("storeId", storeId.toString())
                        .param("employeeId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        verify(getSalesHistoryUseCase).getSalesHistory(argThat(query -> actorId.equals(query.employeeId())));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MANAGER", "EMPLOYEE", "VIEWER"})
    void GET_salesHistory_member_otherStore_403(String role) throws Exception {
        mockJwt(role);

        mockMvc.perform(get("/api/v1/sales/history").param("storeId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(getSalesHistoryUseCase);
    }
}
