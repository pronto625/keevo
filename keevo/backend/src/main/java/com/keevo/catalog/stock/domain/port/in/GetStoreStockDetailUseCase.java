package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import org.springframework.data.domain.Page;

/**
 * Use case for retrieving paginated product stock list for a specific store.
 * Story 3.2.
 */
public interface GetStoreStockDetailUseCase {
    Page<StoreProductStockEntry> execute(GetStoreStockDetailQuery query);
}
