package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;

/**
 * A line of a credit note (Story 14.7): the credited part of the invoice line {@code invoicePosition}, with the same
 * description, unit price and VAT treatment / rate. Amounts are stored positive (francs); the « avoir » meaning and the
 * minus sign are presentation.
 */
public record CreditNoteLine(
        int position,
        int invoicePosition,
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
