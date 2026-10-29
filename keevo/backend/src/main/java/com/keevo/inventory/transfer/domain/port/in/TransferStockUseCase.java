package com.keevo.inventory.transfer.domain.port.in;

import com.keevo.inventory.transfer.domain.model.StockTransfer;

/**
 * TransferStockUseCase — input port for executing a stock transfer.
 * Story 3.3.
 */
public interface TransferStockUseCase {
    StockTransfer execute(TransferStockCommand command);
}
