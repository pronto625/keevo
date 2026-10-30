package com.keevo.sync.sync.application.service;

import com.keevo.shared.application.port.sync.SyncOperationHandler;
import org.junit.jupiter.api.Test;

import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncOperationHandlerRegistryTest {

    private static final Set<String> INSTALLED = Set.of("admin", "catalog", "core", "crm", "inventory",
            "messaging", "pos", "reporting", "store", "sync");

    private SyncOperationHandlerRegistry createRegistry(SyncOperationHandler... handlers) {
        return new SyncOperationHandlerRegistry(List.of(handlers), INSTALLED);
    }

    private SyncOperationHandler handlerFor(String... types) {
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.supportedTypes()).thenReturn(Set.of(types));
        when(handler.moduleId()).thenReturn("pos");
        return handler;
    }

    /** A named (non-mock) handler so the boot error can be checked to name it. */
    static final class GhostModuleHandler implements SyncOperationHandler {
        private final String moduleId;
        private final Set<String> types;

        GhostModuleHandler(String moduleId, String... types) {
            this.moduleId = moduleId;
            this.types = Set.of(types);
        }

        @Override public Set<String> supportedTypes() { return types; }
        @Override public String moduleId() { return moduleId; }
        @Override public SyncOperationResult handle(SyncOperation operation, UUID actorId, String tenantId) {
            return null;
        }
    }

    @Test
    void handlerWithUnknownModule_failsNamingTheHandler() {
        assertThatThrownBy(() -> createRegistry(new GhostModuleHandler("ghost", "CREATE_GHOST")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(GhostModuleHandler.class.getName())
                .hasMessageContaining("'ghost'");
    }

    @Test
    void handlerWithoutModule_fails() {
        assertThatThrownBy(() -> createRegistry(new GhostModuleHandler(null, "CREATE_GHOST")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(GhostModuleHandler.class.getName());
    }

    @Test
    void handlerWithoutTypes_fails() {
        assertThatThrownBy(() -> createRegistry(new GhostModuleHandler("pos")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(GhostModuleHandler.class.getName());
    }

    @Test
    void sameTypeInTwoHandlers_failsNamingTheType() {
        assertThatThrownBy(() -> createRegistry(new GhostModuleHandler("pos", "CREATE_SALE"),
                new GhostModuleHandler("crm", "CREATE_CLIENT", "CREATE_SALE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATE_SALE")
                .hasMessageContaining(GhostModuleHandler.class.getName());
    }

    @Test
    void springConstructor_readsInstalledModulesFromTheModuleRegistry() {
        ModuleRegistry moduleRegistry = mock(ModuleRegistry.class);
        ModuleManifest pos = mock(ModuleManifest.class);
        when(pos.id()).thenReturn("pos");
        when(moduleRegistry.installed()).thenReturn(List.of(pos));

        var registry = new SyncOperationHandlerRegistry(List.of(new GhostModuleHandler("pos", "CREATE_SALE")),
                moduleRegistry);
        assertThat(registry.resolve("CREATE_SALE")).isPresent();
        assertThatThrownBy(() -> new SyncOperationHandlerRegistry(
                List.of(new GhostModuleHandler("crm", "CREATE_CLIENT")), moduleRegistry))
                .hasMessageContaining("'crm'");
    }

    @Test
    void nullType_resolvesEmpty() {
        assertThat(createRegistry(handlerFor("CREATE_SALE")).resolve(null)).isEmpty();
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
