package com.keevo.catalog.stock.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * SetStockThresholdUseCase — sets the minimum stock threshold on a product.
 *
 * <p>Uses the copy-with pattern ({@code Product.withMinimumThreshold}) to
 * preserve immutability in the domain entity.
 *
 * Story 2.3.
 */
@Service
public class SetStockThresholdUseCase {

    private final ProductRepository productRepository;

    public SetStockThresholdUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * @param productId    target product
     * @param threshold    new minimum threshold (must be >= 0)
     * @return updated product
     * @throws DomainException PRODUCT_NOT_FOUND if product does not exist
     * @throws IllegalArgumentException if threshold is negative
     */
    @Transactional
    public Product execute(UUID productId, int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("Threshold cannot be negative");
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));

        Product updated = product.withMinimumThreshold(threshold);
        return productRepository.save(updated);
    }
}
