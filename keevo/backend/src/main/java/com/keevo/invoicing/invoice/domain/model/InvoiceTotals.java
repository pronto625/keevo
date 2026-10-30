package com.keevo.invoicing.invoice.domain.model;

/** Totals of an invoice in francs: sums of the lines. */
public record InvoiceTotals(long totalHt, long totalVat, long totalTtc) {

    public static final InvoiceTotals ZERO = new InvoiceTotals(0, 0, 0);
}
