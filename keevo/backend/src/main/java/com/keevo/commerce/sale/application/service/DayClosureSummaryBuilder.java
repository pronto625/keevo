package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DayClosureSummaryBuilder — GoF Builder pattern for computing day closure aggregates.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Computes:
 * <ul>
 *   <li>totalSales — count of COMPLETED sales</li>
 *   <li>totalRevenue — sum of COMPLETED sales totalAmount</li>
 *   <li>topProduct — product with highest total quantity sold</li>
 *   <li>cashAmount/momoAmount — payment breakdown</li>
 *   <li>pendingSalesCount/pendingSalesTotal — PENDING_VALIDATION tracking</li>
 * </ul>
 */
public class DayClosureSummaryBuilder {

    private int totalSales = 0;
    private int totalRevenue = 0;
    private int cashAmount = 0;
    private int momoAmount = 0;
    private int pendingSalesCount = 0;
    private int pendingSalesTotal = 0;

    // Map productId → (productName, totalQty)
    private final Map<String, ProductTally> productQuantities = new HashMap<>();

    private record ProductTally(String name, int quantity) {
        ProductTally add(int qty) {
            return new ProductTally(name, quantity + qty);
        }
    }

    /**
     * Add a single sale to the summary computation.
     * @return this builder for fluent chaining
     */
    public DayClosureSummaryBuilder addSale(Sale sale) {
        if (sale.getStatus() == SaleStatus.COMPLETED) {
            totalSales++;
            totalRevenue += sale.getTotalAmount();

            if (sale.getPaymentMode() == PaymentMode.CASH) {
                cashAmount += sale.getTotalAmount();
            } else if (sale.getPaymentMode() == PaymentMode.MOBILE_MONEY) {
                momoAmount += sale.getTotalAmount();
            }

            // Aggregate product quantities for top product calculation
            for (SaleItem item : sale.getItems()) {
                String productId = item.getProductId().toString();
                productQuantities.merge(productId,
                        new ProductTally(item.getProductName(), item.getQuantity()),
                        (old, add) -> old.add(add.quantity()));
            }
        } else if (sale.getStatus() == SaleStatus.PENDING_VALIDATION) {
            pendingSalesCount++;
            pendingSalesTotal += sale.getTotalAmount();
        }
        // CANCELLED sales are ignored entirely

        return this;
    }

    /**
     * Add multiple sales to the summary computation.
     * @return this builder for fluent chaining
     */
    public DayClosureSummaryBuilder addSales(List<Sale> sales) {
        sales.forEach(this::addSale);
        return this;
    }

    /**
     * Build the final DayClosureSummary.
     * @return computed summary
     */
    public DayClosureSummary build() {
        // Find top product by quantity
        String topProductId = null;
        String topProductName = null;
        int topProductQty = 0;

        for (var entry : productQuantities.entrySet()) {
            if (entry.getValue().quantity() > topProductQty) {
                topProductId = entry.getKey();
                topProductName = entry.getValue().name();
                topProductQty = entry.getValue().quantity();
            }
        }

        return new DayClosureSummary(
                totalSales,
                totalRevenue,
                topProductId,
                topProductName,
                topProductQty,
                cashAmount,
                momoAmount,
                pendingSalesCount,
                pendingSalesTotal
        );
    }
}
