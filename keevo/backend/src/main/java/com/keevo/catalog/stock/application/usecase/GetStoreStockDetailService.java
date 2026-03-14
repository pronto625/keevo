package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailQuery;
import com.keevo.catalog.stock.domain.port.in.GetStoreStockDetailUseCase;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GetStoreStockDetailService — application service for paginated store stock detail.
 * Story 3.2.
 */
@Service
public class GetStoreStockDetailService implements GetStoreStockDetailUseCase {

    private final MultiStoreStockRepository repo;

    public GetStoreStockDetailService(MultiStoreStockRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoreProductStockEntry> execute(GetStoreStockDetailQuery query) {
        var pageable = PageRequest.of(query.page(), query.size());
        return repo.getStoreStockDetail(query.storeId(), query.sortLowFirst(), pageable);
    }
}
