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
 * PartialScopeResolver — resolves products in selected categories for inventory counting.
 *
 * <p>GoF Strategy: concrete strategy for PARTIAL inventory scope.
 * Filters by category IDs after store-level stock lookup.
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

        // 1. Get stock levels for the store
        List<StockLevel> stockLevels = stockLevelRepository.findAllByStoreId(storeId);
        if (stockLevels.isEmpty()) return List.of();

        // 2. Get eligible products in selected categories
        Set<UUID> categoryProductIds = productRepository.findByCategoryIds(categoryIds).stream()
                .filter(p -> !p.getArchived())
                .map(Product::getId)
                .collect(Collectors.toSet());

        // 3. Intersect: keep only stock levels whose productId is in category-filtered set
        List<StockLevel> filteredStockLevels = stockLevels.stream()
                .filter(sl -> categoryProductIds.contains(sl.getProductId()))
                .toList();
        if (filteredStockLevels.isEmpty()) return List.of();

        // 4. Fetch product info for filtered products
        List<UUID> productIds = filteredStockLevels.stream()
                .map(StockLevel::getProductId)
                .distinct()
                .toList();
        Map<UUID, Product> productMap = productRepository.findAllByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        Map<UUID, String> photoUrls = productRepository.findPhotoUrlsByIds(productIds);

        // 5. Build count lookup
        Map<String, InventoryCount> countLookup = buildCountLookup(existingCounts);

        // 6. Build rows
        List<InventoryProductRow> rows = new ArrayList<>();
        for (StockLevel sl : filteredStockLevels) {
            Product product = productMap.get(sl.getProductId());
            if (product == null) continue;

            String key = compositeKey(sl.getProductId(), sl.getVariantId());
            InventoryCount count = countLookup.get(key);
            Integer physicalQty = count != null ? count.getPhysical() : null;

            rows.add(InventoryProductRow.of(
                    sl.getProductId(),
                    product.getName(),
                    product.getSku(),
                    photoUrls.get(sl.getProductId()),
                    sl.getVariantId(),
                    null,
                    sl.getQuantity(),
                    physicalQty));
        }

        // 7. Sort alphabetically
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
