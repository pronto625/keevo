package com.keevo.catalog.stock.adapter.in.web;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.stock.application.usecase.*;
import com.keevo.catalog.stock.domain.entity.MovementType;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.entity.StockMovement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StockControllerTest — unit tests for StockController REST endpoints.
 * Uses standaloneSetup (no Spring context).
 * Story 2.3.
 */
@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock private GetCurrentStockUseCase getCurrentStockUseCase;
    @Mock private RecordStockEntryUseCase recordStockEntryUseCase;
    @Mock private AdjustStockUseCase adjustStockUseCase;
    @Mock private SetStockThresholdUseCase setStockThresholdUseCase;
    @Mock private GetStockMovementHistoryUseCase getStockMovementHistoryUseCase;

    @InjectMocks private StockController stockController;

    private MockMvc mockMvc;
    private UUID productId;
    private UUID storeId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(stockController).build();

        productId = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        actorId   = UUID.randomUUID();

        // Wire SecurityContext so actorIdFromContext() works
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actorId, null, List.of()));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Product sampleProduct() {
        return new Product(
            productId, "Test Product", null, "KEV-TST001",
            UUID.randomUUID(), 1000, 500, 0, null,
            false, ProductStatus.ACTIVE, 5,
            Instant.now(), Instant.now());
    }

    private StockLevel sampleLevel() {
        return new StockLevel(UUID.randomUUID(), productId, null, storeId, 10, Instant.now());
    }

    private StockMovement sampleMovement(MovementType type) {
        return new StockMovement(
            UUID.randomUUID(), productId, null, storeId,
            type, 0, 10, 10, actorId, null, Instant.now());
    }

    // ── GET /stock ────────────────────────────────────────────────────

    @Test
    void get_current_stock_should_return_200() throws Exception {
        var snapshot = new GetCurrentStockUseCase.StockSnapshot(
            sampleProduct(), List.of(sampleLevel()));
        when(getCurrentStockUseCase.execute(productId)).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/products/{id}/stock", productId))
            .andExpect(status().isOk());
    }

    // ── POST /stock/entry ─────────────────────────────────────────────

    @Test
    void record_stock_entry_should_return_201() throws Exception {
        when(recordStockEntryUseCase.execute(any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(sampleMovement(MovementType.STOCK_ENTRY));

        String body = """
            {
                "storeId": "%s",
                "quantity": 10,
                "notes": "reception"
            }""".formatted(storeId);

        mockMvc.perform(post("/api/v1/products/{id}/stock/entry", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    // ── POST /stock/adjust ────────────────────────────────────────────

    @Test
    void adjust_stock_should_return_201() throws Exception {
        when(adjustStockUseCase.execute(any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(sampleMovement(MovementType.ADJUSTMENT));

        String body = """
            {
                "storeId": "%s",
                "newQuantity": 15,
                "notes": "inventaire"
            }""".formatted(storeId);

        mockMvc.perform(post("/api/v1/products/{id}/stock/adjust", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    // ── PATCH /threshold ──────────────────────────────────────────────

    @Test
    void set_threshold_should_return_200() throws Exception {
        when(setStockThresholdUseCase.execute(eq(productId), anyInt()))
            .thenReturn(sampleProduct());

        String body = """
            { "minimumThreshold": 5 }""";

        mockMvc.perform(patch("/api/v1/products/{id}/threshold", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    // ── GET /stock/history ────────────────────────────────────────────

    @Test
    void get_history_should_return_200() throws Exception {
        var page = new PageImpl<>(List.of(sampleMovement(MovementType.STOCK_ENTRY)), PageRequest.of(0, 20), 1);
        when(getStockMovementHistoryUseCase.execute(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(page);

        mockMvc.perform(get("/api/v1/products/{id}/stock/history", productId))
            .andExpect(status().isOk());
    }

    @Test
    void get_history_with_all_filters_should_return_200() throws Exception {
        var page = new PageImpl<>(List.of(sampleMovement(MovementType.ADJUSTMENT)), PageRequest.of(0, 10), 1);
        when(getStockMovementHistoryUseCase.execute(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(page);

        mockMvc.perform(get("/api/v1/products/{id}/stock/history", productId)
                .param("type", "ADJUSTMENT")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to",   "2026-03-01T00:00:00Z")
                .param("storeId", storeId.toString())
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk());
    }
}
