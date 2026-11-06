package com.keevo.invoicing.invoice.domain.model;

/**
 * Totals of an invoice in francs (Story 14.1; Story 20.5 adds the document-level figures).
 *
 * @param totalHt  the amount without VAT, discount deducted, delivery excluded
 * @param totalVat the document's VAT
 * @param totalTtc what the client owes: {@code totalHt + totalVat + delivery}
 * @param subtotal the sum of the lines as entered (HT with prices without VAT, TTC with prices VAT included), before
 *                 the discount
 * @param discount the discount ({@code ≤ subtotal})
 * @param delivery the delivery fee (outside the VAT base)
 */
public record InvoiceTotals(long totalHt, long totalVat, long totalTtc, long subtotal, long discount, long delivery) {

    public static final InvoiceTotals ZERO = new InvoiceTotals(0, 0, 0);

    /**
     * Pre-20.5 shape — no discount, no delivery; the subtotal is the TTC (prices VAT included, the Keevo default and
     * every credit note).
     */
    public InvoiceTotals(long totalHt, long totalVat, long totalTtc) {
        this(totalHt, totalVat, totalTtc, totalTtc, 0, 0);
    }
}
