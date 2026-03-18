package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.catalog.product.adapter.out.persistence.ProductSpringRepository;
import com.keevo.commerce.sale.domain.port.out.ProductStatusPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ProductStatusPortAdapter — queries the product table to resolve product status.
 * Story 4.3 — bridges the sale domain to the catalog persistence layer.
 */
@Component
public class ProductStatusPortAdapter implements ProductStatusPort {

    private final ProductSpringRepository productRepository;

    public ProductStatusPortAdapter(ProductSpringRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public String getProductStatus(UUID productId) {
        return productRepository.findById(productId)
                .map(entity -> entity.getStatus().name())
                .orElse("UNKNOWN");
    }
}
