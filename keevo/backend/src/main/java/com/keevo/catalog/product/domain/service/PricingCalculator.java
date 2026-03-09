package com.keevo.catalog.product.domain.service;

import com.keevo.shared.domain.model.Money;

/**
 * PricingCalculator — Pure domain service (no Spring dependency).
 *
 * <p>GoF Pattern: Strategy — implements the pricing calculation algorithm as a
 * standalone, stateless, injectable service.
 *
 * <p>Calculation rules (XAF integer arithmetic):
 * <ul>
 *   <li>totalCost = buyPrice + transportCost
 *   <li>grossMarginXaf = sellingPrice - totalCost  (can be negative)
 *   <li>marginPercentage = (grossMarginXaf / totalCost) * 100  → 0.0 if totalCost == 0
 *   <li>isLoss = grossMarginXaf < 0
 * </ul>
 */
public class PricingCalculator {

    /**
     * Calculate the margin for a product.
     *
     * @param buyPrice      purchase cost (non-null, non-negative)
     * @param transportCost logistics cost (non-null, non-negative)
     * @param sellingPrice  catalogue selling price (non-null, non-negative)
     * @return {@link MarginCalculation} with all computed fields
     * @throws IllegalArgumentException if any argument is null
     */
    public MarginCalculation calculateMargin(Money buyPrice, Money transportCost, Money sellingPrice) {
        if (buyPrice == null) throw new IllegalArgumentException("buyPrice must not be null");
        if (transportCost == null) throw new IllegalArgumentException("transportCost must not be null");
        if (sellingPrice == null) throw new IllegalArgumentException("sellingPrice must not be null");

        // totalCost = buyPrice + transportCost
        Money totalCost = buyPrice.add(transportCost);

        // grossMarginXaf = sellingPrice - totalCost (can be negative)
        int grossMarginXaf = sellingPrice.value() - totalCost.value();

        // marginPercentage — base = totalCost (AC2: taux de markup sur coût)
        // guard against division by zero when totalCost == 0
        double marginPercentage = 0.0;
        if (totalCost.value() != 0) {
            marginPercentage = ((double) grossMarginXaf / totalCost.value()) * 100.0;
        }

        boolean isLoss = grossMarginXaf < 0;

        return new MarginCalculation(totalCost, grossMarginXaf, marginPercentage, isLoss);
    }
}
