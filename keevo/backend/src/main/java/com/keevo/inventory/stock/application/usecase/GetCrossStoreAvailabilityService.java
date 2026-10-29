package com.keevo.inventory.stock.application.usecase;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.inventory.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.inventory.stock.domain.port.in.GetCrossStoreAvailabilityQuery;
import com.keevo.inventory.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
import com.keevo.inventory.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GetCrossStoreAvailabilityService — returns stock of a product across all active stores.
 *
 * <p>Validates that the product exists, then delegates to the multi-store repository
 * for the native SQL join (stores × stock_levels).
 *
 * Story 3.4. GoF: Query Object (input), Strategy (repository implementation).
 */
@Service
public class GetCrossStoreAvailabilityService implements GetCrossStoreAvailabilityUseCase {

    private final ProductQueryPort          productQuery; // 12.2a: catalog public API
    private final MultiStoreStockRepository multiStoreStockRepository;

    public GetCrossStoreAvailabilityService(ProductQueryPort productQuery,
                                            MultiStoreStockRepository multiStoreStockRepository) {
        this.productQuery              = productQuery;
        this.multiStoreStockRepository = multiStoreStockRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Result execute(GetCrossStoreAvailabilityQuery query) {
        ProductView product = productQuery.findById(TenantContext.getCurrentTenant(), query.productId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + query.productId()));

        List<CrossStoreAvailabilityEntry> entries =
                multiStoreStockRepository.getProductAvailability(query.productId());

        return new Result(product, entries);
    }
}
