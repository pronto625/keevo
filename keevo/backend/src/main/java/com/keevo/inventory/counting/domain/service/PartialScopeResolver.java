package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * PartialScopeResolver — resolves products in selected categories for inventory counting.
 *
 * <p>GoF Strategy: concrete strategy for PARTIAL inventory scope.
 * Starts from the active products of the selected categories and joins the store's stock
 * levels; a product never stocked in the store is counted with a theoretical quantity of 0.
 */
@Component
public class PartialScopeResolver implements InventoryScopeResolver {

    private final StockLevelRepository stockLevelRepository;
    private final ProductRepository productRepository;

    public PartialScopeResolver(StockLevelRepository stockLevelRepository,
                                ProductRepository productRepository) {
        this.stockLevelRepository = stockLevelRepository;
        this.productRepository = productRepository;
    }

    @Override
    public InventoryScope supportedScope() {
        return InventoryScope.PARTIAL;
    }

    @Override
    public List<InventoryProductRow> resolveProducts(UUID storeId, List<UUID> categoryIds,
                                                      List<InventoryCount> existingCounts) {
        if (categoryIds == null || categoryIds.isEmpty()) return List.of();

        List<Product> products = productRepository.findByCategoryIds(categoryIds);
        if (products.isEmpty()) return List.of();

        List<StockLevel> stockLevels = stockLevelRepository.findAllByStoreId(storeId);
        Map<UUID, String> photoUrls = productRepository.findPhotoUrlsByIds(
                products.stream().map(Product::getId).toList());

        return InventoryRowsAssembler.assemble(products, stockLevels, photoUrls, existingCounts);
    }
}
