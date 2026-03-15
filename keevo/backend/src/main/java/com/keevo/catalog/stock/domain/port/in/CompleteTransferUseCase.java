package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.stock.domain.model.StockTransfer;

/**
 * CompleteTransferUseCase — input port for receiving (completing) a stock transfer.
 *
 * <p>Invoked at the destination store when goods are physically received.
 * Credits the destination stock level and marks the transfer as COMPLETED.
 *
 * Story 3.3.
 */
public interface CompleteTransferUseCase {
    StockTransfer execute(CompleteTransferCommand command);
}
