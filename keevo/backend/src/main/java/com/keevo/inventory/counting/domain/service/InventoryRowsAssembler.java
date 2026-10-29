package com.keevo.inventory.counting.domain.service;

import com.keevo.catalog.api.ProductView;
import com.keevo.inventory.stock.domain.entity.StockLevel;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * InventoryRowsAssembler — builds the counting rows of a scope from its eligible products.
 *
 * <p>The scope is driven by the products, not by {@code stock_levels}: a product that has
 * never been stocked in the store has no row there yet but must still be counted, with a
 * theoretical quantity of 0 (variant {@code null}). Products that do have rows keep one
 * counting row per stock level (product/variant pair), as before.
 */
final class InventoryRowsAssembler {

    private InventoryRowsAssembler() {}

    static List<InventoryProductRow> assemble(Collection<ProductView> eligibleProducts,
                                              List<StockLevel> storeStockLevels,
                                              Map<UUID, String> photoUrls,
                                              List<InventoryCount> existingCounts) {
        Map<UUID, ProductView> productMap = eligibleProducts.stream()
                .filter(p -> !p.archived())
                .collect(Collectors.toMap(ProductView::id, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        if (productMap.isEmpty()) return List.of();

        Map<UUID, List<StockLevel>> levelsByProduct = new HashMap<>();
        for (StockLevel sl : storeStockLevels != null ? storeStockLevels : List.<StockLevel>of()) {
            if (!productMap.containsKey(sl.getProductId())) continue; // out of scope or archived
            levelsByProduct.computeIfAbsent(sl.getProductId(), k -> new ArrayList<>()).add(sl);
        }

        Map<String, InventoryCount> countLookup = buildCountLookup(existingCounts);
        Map<UUID, String> photos = photoUrls != null ? photoUrls : Map.of();

        List<InventoryProductRow> rows = new ArrayList<>();
        for (ProductView product : productMap.values()) {
            List<StockLevel> levels = levelsByProduct.get(product.id());
            if (levels == null || levels.isEmpty()) {
                rows.add(row(product, photos, null, 0, countLookup));
            } else {
                for (StockLevel sl : levels) {
                    rows.add(row(product, photos, sl.getVariantId(), sl.getQuantity(), countLookup));
                }
            }
        }

        rows.sort(Comparator.comparing(InventoryProductRow::productName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static InventoryProductRow row(ProductView product, Map<UUID, String> photos, UUID variantId,
                                           int theoreticalQty, Map<String, InventoryCount> countLookup) {
        InventoryCount count = countLookup.get(compositeKey(product.id(), variantId));
        Integer physicalQty = count != null ? count.getPhysical() : null;
        // J17: a counted row reads its gap against the theoretical snapshot saved with the
        // count — the same rule as the gap report and the validation — not against the live
        // stock (otherwise the counting banner and the report disagree).
        if (count != null && physicalQty != null) {
            theoreticalQty = count.getTheoretical();
        }
        return InventoryProductRow.of(
                product.id(),
                product.name(),
                product.sku(),
                photos.get(product.id()),
                variantId,
                null, // variantLabel — future variant enrichment
                theoreticalQty,
                physicalQty);
    }

    private static Map<String, InventoryCount> buildCountLookup(List<InventoryCount> counts) {
        if (counts == null || counts.isEmpty()) return Map.of();
        return counts.stream()
                .collect(Collectors.toMap(
                        c -> compositeKey(c.getProductId(), c.getVariantId()),
                        Function.identity(),
                        (a, b) -> a));
    }

    private static String compositeKey(UUID productId, UUID variantId) {
        return productId + ":" + (variantId != null ? variantId : "null");
    }
}
