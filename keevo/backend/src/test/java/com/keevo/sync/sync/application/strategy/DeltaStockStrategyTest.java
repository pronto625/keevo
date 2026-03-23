package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeltaStockStrategyTest {

    @Mock private EntityManager entityManager;
    @Mock private Query nativeQuery;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DeltaStockStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DeltaStockStrategy(entityManager, eventPublisher);
    }

    @Test
    void evaluate_stockPositive_returnsNoConflict() {
        var op = saleOperation("p1", "s1", 3);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        mockStockQuery(10); // stock = 10 after delta
        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNotNull();
        assertThat(result.conflictType()).isNull();
        assertThat(result.strategy()).isEqualTo("DELTA_SUM");
        verify(eventPublisher).publishEvent(any(StockConflictResolvedEvent.class));
    }

    @Test
    void evaluate_stockZero_returnsNoConflict() {
        var op = saleOperation("p1", "s1", 5);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        mockStockQuery(0);
        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNotNull();
        assertThat(result.conflictType()).isNull();
    }

    @Test
    void evaluate_stockNegative_returnsStockNegativeConflict() {
        var op = saleOperation("p1", "s1", 3);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        mockStockQuery(-1);
        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNotNull();
        assertThat(result.conflictType()).isEqualTo("STOCK_NEGATIVE");
        assertThat(result.strategy()).isEqualTo("DELTA_SUM");
        verify(eventPublisher).publishEvent(any(StockConflictResolvedEvent.class));
    }

    @Test
    void evaluate_stockNegative_conflictDataHasCorrectFields() {
        var op = saleOperation("p1", "s1", 3);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        mockStockQuery(-1);
        mockProductName("Coca-Cola 50cl");
        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result.conflictData()).containsEntry("conflictType", "STOCK_NEGATIVE");
        assertThat(result.conflictData()).containsEntry("productId", "p1");
        assertThat(result.conflictData()).containsEntry("storeId", "s1");
        assertThat(result.conflictData()).containsKey("deltaApplied");
        assertThat(result.conflictData()).containsKey("resultingStock");
        assertThat(result.conflictData()).containsKey("previousStock");
    }

    @Test
    void evaluate_stockNegative_previousStockCalculatedCorrectly() {
        // Stock went from 2 to -1 after selling 3
        var op = saleOperation("p1", "s1", 3);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        mockStockQuery(-1); // resulting stock after delta
        mockProductName("Test Product");
        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        // previousStock = resultingStock - deltaApplied = -1 - (-3) = 2
        assertThat(result.conflictData().get("previousStock")).isEqualTo(2);
        assertThat(result.conflictData().get("resultingStock")).isEqualTo(-1);
        assertThat(result.conflictData().get("deltaApplied")).isEqualTo(-3);
    }

    @Test
    void evaluate_missingProductId_returnsNoConflict() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
    }

    @Test
    void evaluate_handlerStatusRejected_skipsEvaluation() {
        var op = saleOperation("p1", "s1", 3);
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.REJECTED, null, "ERROR");

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
        verifyNoInteractions(entityManager);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private SyncOperation saleOperation(String productId, String storeId, int quantity) {
        return new SyncOperation("op-1", "CREATE_SALE", "sale-1",
                Map.of("saleId", "sale-1", "storeId", storeId,
                        "items", java.util.List.of(Map.of("productId", productId, "quantity", quantity))),
                Instant.now());
    }

    private void mockStockQuery(int quantity) {
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(quantity);
    }

    private void mockProductName(String name) {
        Query nameQuery = mock(Query.class);
        lenient().when(entityManager.createNativeQuery(argThat(s -> s != null && s.contains("products")))).thenReturn(nameQuery);
        lenient().when(nameQuery.setParameter(anyInt(), any())).thenReturn(nameQuery);
        lenient().when(nameQuery.getSingleResult()).thenReturn(name);
    }
}
