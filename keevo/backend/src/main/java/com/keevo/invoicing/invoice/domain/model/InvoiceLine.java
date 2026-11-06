package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A computed invoice line (Story 14.1): amounts in francs. {@code vatRate} is the rate actually applied (percent,
 * {@code 0} for an exempt / State-borne line or a non-VAT-registered seller).
 *
 * <p>Story 20.5: {@code productId} / {@code reference} as entered ({@link LineInput}). On a document with prices
 * without VAT, {@code totalVat} / {@code totalTtc} are the per-line split, for display: the document's VAT is
 * computed once on the whole document ({@link VatCalculator}) and its totals are authoritative.
 */
public record InvoiceLine(
        int position,
        String description,
        BigDecimal quantity,
        long unitPrice,
        VatTreatment treatment,
        BigDecimal vatRate,
        long totalHt,
        long totalVat,
        long totalTtc,
        UUID productId,
        String reference) {

    /** Pre-20.5 shape: no product, no reference. */
    public InvoiceLine(int position, String description, BigDecimal quantity, long unitPrice, VatTreatment treatment,
                       BigDecimal vatRate, long totalHt, long totalVat, long totalTtc) {
        this(position, description, quantity, unitPrice, treatment, vatRate, totalHt, totalVat, totalTtc, null, null);
    }

    /** The mention printed on the line: « Exonérée » / « Prise en charge État », {@code null} at the standard rate. */
    public String vatMention() {
        return treatment.mention();
    }
}
