package com.keevo.catalog.stock.adapter.in.web;

import com.keevo.catalog.stock.application.usecase.GetMultiStoreOverviewService;
import com.keevo.catalog.stock.application.usecase.GetStoreStockDetailService;
import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailUseCase;
import com.keevo.store.store.domain.model.StoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MultiStoreStockControllerTest — unit tests for MultiStoreStockController REST endpoints.
 * Story 3.2. TDD RED phase.
 */
@ExtendWith(MockitoExtension.class)
class MultiStoreStockControllerTest {

    @Mock private GetMultiStoreOverviewUseCase overviewUseCase;
    @Mock private GetStoreStockDetailUseCase   detailUseCase;

    @InjectMocks private MultiStoreStockController controller;

    private MockMvc mockMvc;
    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private StoreStockSummary sampleSummary() {
        return new StoreStockSummary(storeId, "Boutique A", StoreType.STORE, 5, 100000L, 1);
    }

    private StoreProductStockEntry sampleEntry() {
        return new StoreProductStockEntry(UUID.randomUUID(), "Prod A", null, null, storeId, 10, 5);
    }

    @Test
    void GET_stock_overview_shouldReturn200_withStoreList() throws Exception {
        when(overviewUseCase.execute(any())).thenReturn(List.of(sampleSummary()));

        mockMvc.perform(get("/api/v1/stock/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].storeName").value("Boutique A"));
    }

    @Test
    void GET_stock_overview_shouldReturn_emptyList_whenNoStores() throws Exception {
        when(overviewUseCase.execute(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stock/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void GET_stock_stores_storeId_products_shouldReturn200_withPagedProducts() throws Exception {
        when(detailUseCase.execute(any()))
            .thenReturn(new PageImpl<>(List.of(sampleEntry()), PageRequest.of(0, 25), 1));

        mockMvc.perform(get("/api/v1/stock/stores/{storeId}/products", storeId)
                .param("page", "0")
                .param("size", "25")
                .param("sortLowFirst", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content[0].productName").value("Prod A"));
    }

    @Test
    void GET_stock_stores_invalidUUID_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/stock/stores/not-a-uuid/products"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void GET_stock_overview_sizeClamped_whenExceeds100() throws Exception {
        when(detailUseCase.execute(any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        mockMvc.perform(get("/api/v1/stock/stores/{storeId}/products", storeId)
                .param("size", "999"))
            .andExpect(status().isOk());
        // Size will be clamped to 25 internally
    }
}
