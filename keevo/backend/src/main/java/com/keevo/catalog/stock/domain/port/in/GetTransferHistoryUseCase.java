package com.keevo.catalog.stock.domain.port.in;

import org.springframework.data.domain.Page;
import com.keevo.catalog.stock.domain.model.StockTransfer;

/**
 * GetTransferHistoryUseCase — input port for transfer history queries.
 * Story 3.3.
 */
public interface GetTransferHistoryUseCase {
    Page<StockTransfer> execute(GetTransferHistoryQuery query);
}
