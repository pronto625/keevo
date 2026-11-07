package com.keevo.reporting.profitability.domain.model;

/**
 * Story 20.9 (R4) — the period's revenue and the part of it whose margin is known.
 *
 * <ul>
 *   <li>{@code totalRevenue} — revenue of the period (sales + invoices − credit notes, a bridged sale once): the same
 *       figure as the dashboard;</li>
 *   <li>{@code revenueWithKnownMargin}, {@code costOfKnownMargin}, {@code grossMarginXaf}, {@code marginPercent} —
 *       lines of a catalogue product (same formula as {@link ProductProfitabilityEntry}: margin on cost);</li>
 *   <li>{@code revenueWithUnknownMargin} over {@code unknownMarginLines} lines — lines without product (« marge
 *       inconnue »): counted in revenue, in no product margin.</li>
 * </ul>
 * Lines are taken before a document's discount and delivery, so the two line totals need not add up to
 * {@code totalRevenue}.
 */
public record ProfitabilitySummary(
        long totalRevenue,
        long revenueWithKnownMargin,
        long costOfKnownMargin,
        long grossMarginXaf,
        double marginPercent,
        long revenueWithUnknownMargin,
        int unknownMarginLines
) {

    public static ProfitabilitySummary of(long totalRevenue, long knownRevenue, long knownCost,
                                          long unknownRevenue, int unknownLines) {
        long margin = knownRevenue - knownCost;
        double percent = knownCost == 0 ? 0.0 : (double) margin / knownCost * 100.0;
        return new ProfitabilitySummary(totalRevenue, knownRevenue, knownCost, margin, percent, unknownRevenue,
                unknownLines);
    }
}
