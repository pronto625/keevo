package com.keevo.inventory.stock.domain.port.in;

import com.keevo.inventory.stock.domain.model.StockTransfer;

/**
 * TransferStockUseCase — input port for executing a stock transfer.
 * Story 3.3.
 */
public interface TransferStockUseCase {
    StockTransfer execute(TransferStockCommand command);
}
