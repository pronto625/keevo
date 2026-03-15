package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;
import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityQuery;
import com.keevo.catalog.stock.domain.port.in.GetCrossStoreAvailabilityUseCase;
import com.keevo.catalog.stock.domain.port.out.MultiStoreStockRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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

    private final ProductRepository         productRepository;
    private final MultiStoreStockRepository multiStoreStockRepository;

    public GetCrossStoreAvailabilityService(ProductRepository productRepository,
                                            MultiStoreStockRepository multiStoreStockRepository) {
        this.productRepository         = productRepository;
        this.multiStoreStockRepository = multiStoreStockRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Result execute(GetCrossStoreAvailabilityQuery query) {
        Product product = productRepository.findById(query.productId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + query.productId()));

        List<CrossStoreAvailabilityEntry> entries =
                multiStoreStockRepository.getProductAvailability(query.productId());

        return new Result(product, entries);
    }
}
