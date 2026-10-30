package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;

/**
 * A computed invoice line (Story 14.1): amounts in francs. {@code vatRate} is the rate actually applied (percent,
 * {@code 0} for an exempt / State-borne line or a non-VAT-registered seller).
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
        long totalTtc) {

    /** The mention printed on the line: « Exonérée » / « Prise en charge État », {@code null} at the standard rate. */
    public String vatMention() {
        return treatment.mention();
    }
}
