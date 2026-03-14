package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewQuery;
import com.keevo.catalog.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GetMultiStoreOverviewService — application service for stock overview.
 * Story 3.2.
 */
@Service
public class GetMultiStoreOverviewService implements GetMultiStoreOverviewUseCase {

    private final MultiStoreStockRepository repo;

    public GetMultiStoreOverviewService(MultiStoreStockRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreStockSummary> execute(GetMultiStoreOverviewQuery query) {
        return repo.getStoreOverviews();
    }
}
