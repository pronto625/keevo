package com.keevo.inventory.counting.domain.service;

import com.keevo.inventory.counting.domain.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * InventoryGapReportBuilder — GoF Builder for constructing the gap report.
 *
 * <p>Steps: set session metadata → addRow for each count → build() to assemble.
 * Story 6.3 — Gap Analysis Report.
 */
public class InventoryGapReportBuilder {

    private UUID sessionId;
    private UUID storeId;
    private String storeName;
    private InventoryScope scope;
    private UUID generatedBy;
    private final List<InventoryGapRow> allRows = new ArrayList<>();

    public InventoryGapReportBuilder sessionId(UUID id) { this.sessionId = id; return this; }
    public InventoryGapReportBuilder storeId(UUID id) { this.storeId = id; return this; }
    public InventoryGapReportBuilder storeName(String name) { this.storeName = name; return this; }
    public InventoryGapReportBuilder scope(InventoryScope s) { this.scope = s; return this; }
    public InventoryGapReportBuilder generatedBy(UUID actor) { this.generatedBy = actor; return this; }

    public InventoryGapReportBuilder addRow(InventoryCount count, int unitPriceXaf) {
        int ecart = count.getEcart();
        long gapValue = (long) Math.abs(ecart) * unitPriceXaf;
        allRows.add(new InventoryGapRow(
                count.getProductId(), count.getProductName(), null,
                null, count.getVariantId(), count.getVariantLabel(),
                count.getTheoretical(), count.getPhysical(), ecart,
                unitPriceXaf, gapValue
        ));
        return this;
    }

    public InventoryGapReportBuilder enrichProductDetails(Map<UUID, ProductDetails> details) {
        List<InventoryGapRow> enriched = new ArrayList<>(allRows.size());
        for (InventoryGapRow row : allRows) {
            ProductDetails d = details.get(row.productId());
            if (d != null) {
                enriched.add(new InventoryGapRow(
                        row.productId(), row.productName(),
                        d.sku(), d.photoUrl(),
                        row.variantId(), row.variantLabel(),
                        row.theoretical(), row.physical(), row.ecart(),
                        row.unitPriceXaf(), row.gapValueXaf()
                ));
            } else {
                enriched.add(row);
            }
        }
        allRows.clear();
        allRows.addAll(enriched);
        return this;
    }

    public InventoryGapReport build() {
        List<InventoryGapRow> concordant = allRows.stream()
                .filter(InventoryGapRow::isConcordant).toList();
        List<InventoryGapRow> surplus = allRows.stream()
                .filter(InventoryGapRow::isSurplus)
                .sorted(Comparator.comparingLong(InventoryGapRow::gapValueXaf).reversed())
                .toList();
        List<InventoryGapRow> shortage = allRows.stream()
                .filter(InventoryGapRow::isShortage)
                .sorted(Comparator.comparingLong(InventoryGapRow::gapValueXaf).reversed())
                .toList();

        InventoryGapSummary summary = new InventoryGapSummary(
                allRows.size(),
                concordant.size(),
                surplus.size(),
                shortage.size(),
                surplus.stream().mapToLong(InventoryGapRow::gapValueXaf).sum(),
                shortage.stream().mapToLong(InventoryGapRow::gapValueXaf).sum()
        );

        return new InventoryGapReport(sessionId, storeId, storeName, scope,
                summary, concordant, surplus, shortage, generatedBy, Instant.now());
    }

    public record ProductDetails(String sku, String photoUrl, String categoryName) {}
}
