package com.keevo.catalog.product.adapter.in.web.dto;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for Product API endpoints
 * 
 * Includes all product information for frontend consumption
 */
public record ProductResponseDto(
        UUID id,
        String name,
        String description,
        String sku,
        UUID categoryId,
        Integer price,
        Integer buyPrice,
        Integer transportCost,
        Integer stockQuantity,
        boolean archived,
        ProductStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Factory method to create DTO from domain entity
     */
    public static ProductResponseDto fromDomain(Product product) {
        return new ProductResponseDto(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getSku(),
                product.getCategoryId(),
                product.getPriceValue(),
                product.getBuyPriceValue(),
                product.getTransportCostValue(),
                product.getStockQuantity(),
                product.getArchived(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}