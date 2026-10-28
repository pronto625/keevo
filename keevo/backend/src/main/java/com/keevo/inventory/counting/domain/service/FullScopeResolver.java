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
 * FullScopeResolver — resolves ALL active products for inventory counting in a store.
 *
 * <p>GoF Strategy: concrete strategy for FULL inventory scope.
 * Starts from the active (non-archived) catalogue and joins the store's stock levels;
 * a product never stocked in the store is counted with a theoretical quantity of 0.
 */
@Component
public class FullScopeResolver implements InventoryScopeResolver {

    private final StockLevelRepository stockLevelRepository;
    private final ProductRepository productRepository;

    public FullScopeResolver(StockLevelRepository stockLevelRepository,
                             ProductRepository productRepository) {
        this.stockLevelRepository = stockLevelRepository;
        this.productRepository = productRepository;
    }

    @Override
    public InventoryScope supportedScope() {
        return InventoryScope.FULL;
    }

    @Override
    public List<InventoryProductRow> resolveProducts(UUID storeId, List<UUID> categoryIds,
                                                      List<InventoryCount> existingCounts) {
        List<Product> products = productRepository.findAllActive();
        if (products.isEmpty()) return List.of();

        List<StockLevel> stockLevels = stockLevelRepository.findAllByStoreId(storeId);
        Map<UUID, String> photoUrls = productRepository.findPhotoUrlsByIds(
                products.stream().map(Product::getId).toList());

        return InventoryRowsAssembler.assemble(products, stockLevels, photoUrls, existingCounts);
    }
}
