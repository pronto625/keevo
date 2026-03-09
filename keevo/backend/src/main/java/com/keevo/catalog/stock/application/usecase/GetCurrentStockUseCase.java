package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * GetCurrentStockUseCase — returns all stock levels for a product across all stores.
 *
 * <p>Includes the product's minimumThreshold so the client can
 * compute {@code isLow = quantity <= minimumThreshold}.
 *
 * Story 2.3.
 */
@Service
public class GetCurrentStockUseCase {

    private final ProductRepository productRepository;
    private final StockLevelRepository stockLevelRepository;

    public GetCurrentStockUseCase(ProductRepository productRepository,
                                  StockLevelRepository stockLevelRepository) {
        this.productRepository    = productRepository;
        this.stockLevelRepository = stockLevelRepository;
    }

    /**
     * Result containing the product context and its stock levels per store.
     *
     * @param product          the product (carries minimumThreshold)
     * @param stockLevels      stock levels across all stores (may be empty if never stocked)
     */
    public record StockSnapshot(Product product, List<StockLevel> stockLevels) {

        /** True when at least one store is at or below the threshold. */
        public boolean isLowInAnyStore() {
            int threshold = product.getMinimumThreshold();
            if (threshold <= 0) return false;
            return stockLevels.stream()
                    .anyMatch(l -> l.getQuantity() <= threshold);
        }
    }

    /**
     * @param productId target product
     * @return StockSnapshot with product + all stock levels
     * @throws DomainException PRODUCT_NOT_FOUND if product does not exist
     */
    @Transactional(readOnly = true)
    public StockSnapshot execute(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        List<StockLevel> levels = stockLevelRepository.findAllByProduct(productId);
        return new StockSnapshot(product, levels);
    }
}
