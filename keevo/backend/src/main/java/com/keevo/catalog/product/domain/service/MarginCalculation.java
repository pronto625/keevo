package com.keevo.catalog.product.domain.service;

import com.keevo.shared.domain.model.Money;

/**
 * MarginCalculation — Value Object (GoF: Value Object pattern).
 *
 * <p>Immutable result of a margin calculation. All fields are computed
 * from domain inputs and stored as-is — no mutation allowed.
 *
 * <p>Note: {@code grossMargin} is an {@code int} (not Money) because it can be
 * negative when selling below cost (a loss scenario). Money enforces non-negative.
 *
 * @param totalCost         buyPrice + transportCost (always non-negative)
 * @param grossMarginXaf    sellingPrice - totalCost (can be negative → loss, in XAF)
 * @param marginPercentage  (grossMarginXaf / sellingPrice) * 100; 0.0 if sellingPrice == 0
 * @param isLoss            true when grossMarginXaf < 0
 */
public record MarginCalculation(
        Money totalCost,
        int grossMarginXaf,
        double marginPercentage,
        boolean isLoss
) {
    /** Convenience: returns a Money object IF not a loss (grossMargin >= 0). */
    public Money grossMargin() {
        return new Money(Math.max(0, grossMarginXaf));
    }
}
