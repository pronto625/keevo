package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ConflictStrategyRegistryTest {

    @Mock private DeltaStockStrategy deltaStockStrategy;
    @Mock private LastWriteWinsStrategy lastWriteWinsStrategy;
    @Mock private AppendOnlyStrategy appendOnlyStrategy;

    private ConflictStrategyRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConflictStrategyRegistry(deltaStockStrategy, lastWriteWinsStrategy, appendOnlyStrategy);
    }

    @Test
    void resolve_createSale_returnsDeltaStockStrategy() {
        assertThat(registry.resolve("CREATE_SALE")).isSameAs(deltaStockStrategy);
    }

    @Test
    void resolve_stockAdjust_returnsDeltaStockStrategy() {
        assertThat(registry.resolve("STOCK_ADJUST")).isSameAs(deltaStockStrategy);
    }

    @Test
    void resolve_recordStockEntry_returnsDeltaStockStrategy() {
        assertThat(registry.resolve("RECORD_STOCK_ENTRY")).isSameAs(deltaStockStrategy);
    }

    @Test
    void resolve_stockTransfer_returnsDeltaStockStrategy() {
        assertThat(registry.resolve("STOCK_TRANSFER")).isSameAs(deltaStockStrategy);
    }

    @Test
    void resolve_updateProduct_returnsLastWriteWinsStrategy() {
        assertThat(registry.resolve("UPDATE_PRODUCT")).isSameAs(lastWriteWinsStrategy);
    }

    @Test
    void resolve_updateClient_returnsLastWriteWinsStrategy() {
        assertThat(registry.resolve("UPDATE_CLIENT")).isSameAs(lastWriteWinsStrategy);
    }

    @Test
    void resolve_updateSupplier_returnsLastWriteWinsStrategy() {
        assertThat(registry.resolve("UPDATE_SUPPLIER")).isSameAs(lastWriteWinsStrategy);
    }

    @Test
    void resolve_createEmployee_returnsLastWriteWinsStrategy() {
        assertThat(registry.resolve("CREATE_EMPLOYEE")).isSameAs(lastWriteWinsStrategy);
    }

    @Test
    void resolve_createDayClosure_returnsAppendOnlyStrategy() {
        assertThat(registry.resolve("CREATE_DAY_CLOSURE")).isSameAs(appendOnlyStrategy);
    }

    @Test
    void resolve_unknownType_returnsAppendOnlyStrategy() {
        assertThat(registry.resolve("TOTALLY_UNKNOWN")).isSameAs(appendOnlyStrategy);
    }
}
