package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.stock.domain.model.StockTransfer;

/**
 * TransferStockUseCase — input port for executing a stock transfer.
 * Story 3.3.
 */
public interface TransferStockUseCase {
    StockTransfer execute(TransferStockCommand command);
}
