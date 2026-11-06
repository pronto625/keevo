package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line as entered (Story 14.1): free description, quantity &gt; 0 (up to 3 decimals), unit price in francs &ge; 0
 * (VAT included or excluded per the seller's settings), VAT treatment ({@code STANDARD} when null).
 *
 * <p>Story 20.5: {@code productId} — the catalogue product the line comes from (optional, no cross-module key, never
 * checked against the catalogue); {@code reference} — the line's reference as printed (optional, ≤ 100).
 */
public record LineInput(String description, BigDecimal quantity, long unitPrice, VatTreatment treatment,
                        UUID productId, String reference) {

    /** Column {@code numeric(12,3)}. */
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");

    /** Column {@code invoice_items.reference}. */
    public static final int MAX_REFERENCE = 100;

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
        reference = InvoicingSettings.trimToNull(reference);
        if (reference != null && reference.length() > MAX_REFERENCE) {
            throw new com.keevo.shared.domain.exception.DomainException(
                    com.keevo.shared.domain.exception.ErrorCode.VALIDATION_ERROR, "Line reference too long",
                    java.util.Map.of("reason", "La référence de la ligne ne doit pas dépasser 100 caractères"));
        }
    }

    /** Pre-20.5 shape: no product, no reference. */
    public LineInput(String description, BigDecimal quantity, long unitPrice, VatTreatment treatment) {
        this(description, quantity, unitPrice, treatment, null, null);
    }

    /** The input of an already computed line (recomputation at issue, conversion, credit note). */
    public static LineInput of(InvoiceLine line) {
        return new LineInput(line.description(), line.quantity(), line.unitPrice(), line.treatment(),
                line.productId(), line.reference());
    }
}
