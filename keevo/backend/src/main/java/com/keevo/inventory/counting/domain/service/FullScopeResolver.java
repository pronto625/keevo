package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.catalog.stock.domain.port.out.StockLevelRepository;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FullScopeResolver — resolves ALL active products in a store for inventory counting.
 *
 * <p>GoF Strategy: concrete strategy for FULL inventory scope.
 * Uses stock_levels to find products in the store, then enriches with product info.
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
        // 1. Get all stock levels for this store
        List<StockLevel> stockLevels = stockLevelRepository.findAllByStoreId(storeId);
        if (stockLevels.isEmpty()) return List.of();

        // 2. Extract distinct product IDs
        List<UUID> productIds = stockLevels.stream()
                .map(StockLevel::getProductId)
                .distinct()
                .toList();

        // 3. Fetch product info
        Map<UUID, Product> productMap = productRepository.findAllByIds(productIds).stream()
                .filter(p -> !p.getArchived())
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));

        // 4. Fetch photo URLs
        Map<UUID, String> photoUrls = productRepository.findPhotoUrlsByIds(productIds);

        // 5. Build count lookup (key = productId:variantId)
        Map<String, InventoryCount> countLookup = buildCountLookup(existingCounts);

        // 6. Build InventoryProductRow list
        List<InventoryProductRow> rows = new ArrayList<>();
        for (StockLevel sl : stockLevels) {
            Product product = productMap.get(sl.getProductId());
            if (product == null) continue; // archived or missing

            String key = compositeKey(sl.getProductId(), sl.getVariantId());
            InventoryCount count = countLookup.get(key);
            Integer physicalQty = count != null ? count.getPhysical() : null;

            rows.add(InventoryProductRow.of(
                    sl.getProductId(),
                    product.getName(),
                    product.getSku(),
                    photoUrls.get(sl.getProductId()),
                    sl.getVariantId(),
                    null, // variantLabel — future variant enrichment
                    sl.getQuantity(),
                    physicalQty));
        }

        // 7. Sort alphabetically by product name
        rows.sort(Comparator.comparing(InventoryProductRow::productName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private Map<String, InventoryCount> buildCountLookup(List<InventoryCount> counts) {
        if (counts == null || counts.isEmpty()) return Map.of();
        return counts.stream()
                .collect(Collectors.toMap(
                        c -> compositeKey(c.getProductId(), c.getVariantId()),
                        Function.identity(),
                        (a, b) -> a));
    }

    private String compositeKey(UUID productId, UUID variantId) {
        return productId + ":" + (variantId != null ? variantId : "null");
    }
}
