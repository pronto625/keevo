package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;

/**
 * A line as entered (Story 14.1): free description, quantity &gt; 0 (up to 3 decimals), unit price in francs &ge; 0
 * (VAT included or excluded per the seller's settings), VAT treatment ({@code STANDARD} when null).
 */
public record LineInput(String description, BigDecimal quantity, long unitPrice, VatTreatment treatment) {

    /** Column {@code numeric(12,3)}. */
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");

    public LineInput {
        description = InvoicingSettings.trimToNull(description);
        if (description == null) {
            throw new IllegalArgumentException("A line needs a description");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3
                || quantity.compareTo(MAX_QUANTITY) > 0) {
            throw new IllegalArgumentException("A line quantity must be > 0 with at most 3 decimals");
        }
        quantity = quantity.setScale(3, java.math.RoundingMode.UNNECESSARY);
        if (unitPrice < 0) {
            throw new IllegalArgumentException("A line unit price must be >= 0");
        }
        if (treatment == null) {
            treatment = VatTreatment.STANDARD;
        }
    }
}
