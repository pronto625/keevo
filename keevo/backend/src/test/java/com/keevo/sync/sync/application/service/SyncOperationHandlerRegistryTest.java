package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncOperationHandlerRegistryTest {

    private SyncOperationHandlerRegistry createRegistry(SyncOperationHandler... handlers) {
        return new SyncOperationHandlerRegistry(List.of(handlers));
    }

    private SyncOperationHandler handlerFor(String... types) {
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.supportedTypes()).thenReturn(Set.of(types));
        return handler;
    }

    @Test
    void registry_createSale_returnsSaleSyncHandler() {
        var saleHandler = handlerFor("CREATE_SALE");
        var registry = createRegistry(saleHandler);

        assertThat(registry.resolve("CREATE_SALE")).contains(saleHandler);
    }

    @Test
    void registry_createProduct_returnsProductSyncHandler() {
        var productHandler = handlerFor("CREATE_PRODUCT", "UPDATE_PRODUCT", "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT");
        var registry = createRegistry(productHandler);

        assertThat(registry.resolve("CREATE_PRODUCT")).contains(productHandler);
        assertThat(registry.resolve("UPDATE_PRODUCT")).contains(productHandler);
        assertThat(registry.resolve("ARCHIVE_PRODUCT")).contains(productHandler);
        assertThat(registry.resolve("UNARCHIVE_PRODUCT")).contains(productHandler);
    }

    @Test
    void registry_stockTransfer_returnsTransferSyncHandler() {
        var transferHandler = handlerFor("STOCK_TRANSFER");
        var registry = createRegistry(transferHandler);

        assertThat(registry.resolve("STOCK_TRANSFER")).contains(transferHandler);
    }

    @Test
    void registry_createDayClosure_returnsDayClosureSyncHandler() {
        var closureHandler = handlerFor("CREATE_DAY_CLOSURE");
        var registry = createRegistry(closureHandler);

        assertThat(registry.resolve("CREATE_DAY_CLOSURE")).contains(closureHandler);
    }

    @Test
    void registry_stockAdjust_returnsStockAdjustSyncHandler() {
        var stockHandler = handlerFor("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
        var registry = createRegistry(stockHandler);

        assertThat(registry.resolve("STOCK_ADJUST")).contains(stockHandler);
        assertThat(registry.resolve("RECORD_STOCK_ENTRY")).contains(stockHandler);
    }

    @Test
    void registry_unknownType_returnsEmpty() {
        var registry = createRegistry(handlerFor("CREATE_SALE"));

        assertThat(registry.resolve("UNKNOWN_TYPE")).isEmpty();
    }

    @Test
    void registry_clientHandler_resolvesCorrectly() {
        var clientHandler = handlerFor("UPDATE_CLIENT", "ARCHIVE_CLIENT");
        var registry = createRegistry(clientHandler);

        assertThat(registry.resolve("UPDATE_CLIENT")).contains(clientHandler);
        assertThat(registry.resolve("ARCHIVE_CLIENT")).contains(clientHandler);
    }

    @Test
    void registry_supplierHandler_resolvesCorrectly() {
        var supplierHandler = handlerFor("UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER");
        var registry = createRegistry(supplierHandler);

        assertThat(registry.resolve("UPDATE_SUPPLIER")).contains(supplierHandler);
        assertThat(registry.resolve("ARCHIVE_SUPPLIER")).contains(supplierHandler);
    }

    @Test
    void registry_employeeHandler_resolvesCorrectly() {
        var employeeHandler = handlerFor("CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE", "REACTIVATE_EMPLOYEE");
        var registry = createRegistry(employeeHandler);

        assertThat(registry.resolve("CREATE_EMPLOYEE")).contains(employeeHandler);
        assertThat(registry.resolve("REASSIGN_EMPLOYEE")).contains(employeeHandler);
        assertThat(registry.resolve("DEACTIVATE_EMPLOYEE")).contains(employeeHandler);
        assertThat(registry.resolve("REACTIVATE_EMPLOYEE")).contains(employeeHandler);
    }
}
