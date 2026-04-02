package com.keevo.reporting.profitability.adapter.in.rest;

import com.keevo.reporting.profitability.domain.model.*;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase;
import com.keevo.reporting.profitability.domain.port.in.GetProductProfitabilityUseCase.ProfitabilityQuery;
import com.keevo.reporting.profitability.domain.port.in.GetStorePerformanceUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Task 3.1 — TDD RED: ProfitabilityController tests.
 * Story 7.4.
 */
@ExtendWith(MockitoExtension.class)
class ProfitabilityControllerTest {

    @Mock
    private GetProductProfitabilityUseCase profitabilityUseCase;

    @Mock
    private GetStorePerformanceUseCase storePerformanceUseCase;

    @InjectMocks
    private ProfitabilityController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ProductProfitabilityEntry sampleEntry() {
        return new ProductProfitabilityEntry(
                UUID.randomUUID(), "Produit Test", "Catégorie", 5,
                15000L, 4500L, 10500L, 233.3, false, null
        );
    }

    private ProductProfitabilityDetail sampleDetail() {
        return new ProductProfitabilityDetail(
                UUID.randomUUID(), "Produit Test", "Cat", 5,
                15000L, 4500L, 10500L, 233.3, false, null,
                5000, 1000, 500, 1000, 5000, 2500.0,
                List.of(new ProductProfitabilityDetail.DailyMarginEntry("2026-01-01", 1500)),
                UUID.randomUUID(), "Store A", 3
        );
    }

    private StorePerformanceEntry sampleStoreEntry() {
        return new StorePerformanceEntry(1, UUID.randomUUID(), "Store A", 90000L, 10, 9000L, "Top Product", 12.5);
    }

    // ── GET /products ─────────────────────────────────────────────────────────

    @Test
    void GET_products_shouldReturn200_withListOfEntries() throws Exception {
        when(profitabilityUseCase.getEntries(any())).thenReturn(List.of(sampleEntry()));

        mockMvc.perform(get("/api/v1/reporting/profitability/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].productName").value("Produit Test"))
                .andExpect(jsonPath("$.data[0].unitsSold").value(5))
                .andExpect(jsonPath("$.data[0].totalRevenue").value(15000))
                .andExpect(jsonPath("$.data[0].marginPercent").value(233.3))
                .andExpect(jsonPath("$.data[0].isLoss").value(false));
    }

    @Test
    void GET_products_withSortParam_shouldReturn200() throws Exception {
        when(profitabilityUseCase.getEntries(any())).thenReturn(List.of(sampleEntry()));

        mockMvc.perform(get("/api/v1/reporting/profitability/products")
                        .param("sort", "CA_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void GET_products_withDateRange_shouldReturn200() throws Exception {
        when(profitabilityUseCase.getEntries(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reporting/profitability/products")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void GET_products_emptyPeriod_shouldReturn200_withEmptyList() throws Exception {
        when(profitabilityUseCase.getEntries(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/reporting/profitability/products")
                        .param("from", "2020-01-01")
                        .param("to", "2020-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ── GET /products/{productId} ─────────────────────────────────────────────

    @Test
    void GET_productDetail_shouldReturn200_withDetailFields() throws Exception {
        when(profitabilityUseCase.getDetail(any(), any())).thenReturn(sampleDetail());
        UUID productId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reporting/profitability/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productName").value("Produit Test"))
                .andExpect(jsonPath("$.data.currentCataloguePrice").value(5000))
                .andExpect(jsonPath("$.data.minAppliedPrice").value(1000))
                .andExpect(jsonPath("$.data.dailyMarginLast7").isArray())
                .andExpect(jsonPath("$.data.topStoreName").value("Store A"));
    }

    @Test
    void GET_productDetail_unknownId_shouldReturn404() throws Exception {
        when(profitabilityUseCase.getDetail(any(), any()))
                .thenThrow(new DomainException("PRODUCT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/reporting/profitability/products/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("PRODUCT_NOT_FOUND"));
    }

    // ── GET /stores ───────────────────────────────────────────────────────────

    @Test
    void GET_stores_shouldReturn200_withRankedList() throws Exception {
        when(storePerformanceUseCase.getRanking(any())).thenReturn(List.of(sampleStoreEntry()));

        mockMvc.perform(get("/api/v1/reporting/profitability/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].storeName").value("Store A"))
                .andExpect(jsonPath("$.data[0].totalRevenue").value(90000))
                .andExpect(jsonPath("$.data[0].deltaPercent").value(12.5));
    }

    @Test
    void GET_stores_withMetricParam_shouldReturn200() throws Exception {
        when(storePerformanceUseCase.getRanking(any())).thenReturn(List.of(sampleStoreEntry()));

        mockMvc.perform(get("/api/v1/reporting/profitability/stores")
                        .param("metric", "SALES_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
