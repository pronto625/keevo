package com.keevo.inventory.transfer.adapter.in.api;

import com.keevo.catalog.api.ProductStockPresence;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.transfer.domain.port.out.StockTransferRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Story 20.1 — inventory's implementation of the catalogue's {@link ProductStockPresence}: inside the merge transaction
 * the source's stock levels are locked ({@code FOR UPDATE}) then checked for a non-zero quantity, and a transfer in
 * transit blocks the merge too (same check as turning the stock tracking off).
 */
@Component
class CatalogStockPresenceAdapter implements ProductStockPresence {

    private final StockLevelRepository stockLevels;
    private final StockTransferRepository transfers;

    CatalogStockPresenceAdapter(StockLevelRepository stockLevels, StockTransferRepository transfers) {
        this.stockLevels = stockLevels;
        this.transfers = transfers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MergeBlocker lockAndCheckMergeable(String tenantSchema, UUID productId) {
        TenantContext.requireCurrent(tenantSchema);
        if (stockLevels.lockAndHasNonZeroStock(productId)) return MergeBlocker.STOCK;
        if (transfers.existsInTransitForProduct(productId)) return MergeBlocker.TRANSFER_IN_TRANSIT;
        return MergeBlocker.NONE;
    }
}
