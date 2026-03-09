package com.keevo.catalog.product.domain.service;

/**
 * MarginThreshold — GoF: Strategy categorization enum.
 *
 * <p>Categorizes margin percentage into business thresholds with associated
 * color indicators for UI display (GREEN/ORANGE/RED).
 *
 * <p>Rules:
 * <ul>
 *   <li>LOSS        : marginPercentage < 0   → RED
 *   <li>LOW         : 0 ≤ pct < 10           → RED
 *   <li>MODERATE    : 10 ≤ pct < 20          → ORANGE
 *   <li>PROFITABLE  : pct ≥ 20               → GREEN
 * </ul>
 */
public enum MarginThreshold {

    LOSS("RED"),
    LOW("RED"),
    MODERATE("ORANGE"),
    PROFITABLE("GREEN");

    private final String color;

    MarginThreshold(String color) {
        this.color = color;
    }

    public String getColor() {
        return color;
    }

    /**
     * Categorize a margin percentage into the appropriate threshold.
     *
     * @param marginPercentage the calculated margin as percentage (e.g. 23.5 for 23.5%)
     * @return the corresponding MarginThreshold
     */
    public static MarginThreshold categorizeMargin(double marginPercentage) {
        if (marginPercentage < 0) return LOSS;
        if (marginPercentage < 10) return LOW;
        if (marginPercentage < 20) return MODERATE;
        return PROFITABLE;
    }
}
