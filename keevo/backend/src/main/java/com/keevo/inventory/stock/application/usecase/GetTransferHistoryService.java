package com.keevo.inventory.stock.application.usecase;

import com.keevo.inventory.stock.domain.model.StockTransfer;
import com.keevo.inventory.stock.domain.port.in.GetTransferHistoryQuery;
import com.keevo.inventory.stock.domain.port.in.GetTransferHistoryUseCase;
import com.keevo.inventory.stock.domain.port.out.StockTransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GetTransferHistoryService — paginated transfer history query.
 * Story 3.3.
 */
@Service
public class GetTransferHistoryService implements GetTransferHistoryUseCase {

    private final StockTransferRepository transferRepository;

    public GetTransferHistoryService(StockTransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransfer> execute(GetTransferHistoryQuery query) {
        int size = query.size() > 0 ? Math.min(query.size(), 100) : 25;
        Pageable pageable = PageRequest.of(
            query.page(),
            size,
            // id tie-breaker (v1s-16-23): stable order, so paged loading never skips a row.
            Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"))
        );
        return transferRepository.findByFilters(
            query.sourceStoreId(),
            query.destinationStoreId(),
            query.from(),
            query.to(),
            query.storeId(),
            query.status(),
            pageable
        );
    }
}
