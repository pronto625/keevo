package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.catalog.product.domain.entity.Product;

/** Maps the internal {@link Product} to the published {@link ProductView} (Story 12.2a). */
final class ProductViews {

    private ProductViews() {}

    static ProductView of(Product product) {
        return new ProductView(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getSku(),
                product.getCategoryId(),
                product.getPriceValue(),
                product.getBuyPriceValue(),
                product.getTransportCostValue(),
                product.getStockQuantity(),
                Boolean.TRUE.equals(product.getArchived()),
                ProductState.valueOf(product.getStatus().name()),
                product.getMinimumThreshold(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
