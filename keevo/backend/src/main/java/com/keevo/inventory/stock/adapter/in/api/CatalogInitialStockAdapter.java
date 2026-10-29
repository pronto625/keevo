package com.keevo.inventory.stock.adapter.in.api;

import com.keevo.catalog.api.InitialStockPort;
import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockMovementInput;
import com.keevo.inventory.api.StockMovementKind;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Inventory's implementation of the catalog's {@link InitialStockPort} (Story 12.2b): the initial
 * stock of a CSV-imported product becomes a STOCK_ENTRY movement through the inventory's own
 * {@link StockCommandPort}. Declaring the port in {@code catalog :: api} keeps the dependency
 * one-way ({@code inventory → catalog}), so catalog no longer depends on inventory.
 */
@Component
class CatalogInitialStockAdapter implements InitialStockPort {

    private final StockCommandPort stockCommandPort;

    CatalogInitialStockAdapter(StockCommandPort stockCommandPort) {
        this.stockCommandPort = stockCommandPort;
    }

    @Override
    public void recordInitialStock(String tenantSchema, UUID productId, UUID storeId, int quantity, UUID actorId, String notes) {
        TenantContext.requireCurrent(tenantSchema);
        stockCommandPort.recordMovement(tenantSchema, new StockMovementInput(
                productId, null, storeId, StockMovementKind.STOCK_ENTRY, quantity, actorId, notes));
    }
}
