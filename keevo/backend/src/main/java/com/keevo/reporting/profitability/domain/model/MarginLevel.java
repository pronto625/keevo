package com.keevo.reporting.profitability.domain.model;

/**
 * MarginLevel — margin quality thresholds matching PricingCalculatorWidget.
 *
 * <ul>
 *   <li>LOSS   — grossMarginXaf &lt; 0</li>
 *   <li>LOW    — marginPercent &lt; 10%</li>
 *   <li>MODERATE — marginPercent in [10%, 20%)</li>
 *   <li>PROFITABLE — marginPercent ≥ 20%</li>
 * </ul>
 *
 * Thresholds MUST match PricingCalculatorWidget (Flutter) exactly.
 */
public enum MarginLevel {
    LOSS,
    LOW,
    MODERATE,
    PROFITABLE;

    public static MarginLevel of(double marginPercent, boolean isLoss) {
        if (isLoss || marginPercent < 0) return LOSS;
        if (marginPercent < 10) return LOW;
        if (marginPercent < 20) return MODERATE;
        return PROFITABLE;
    }
}
