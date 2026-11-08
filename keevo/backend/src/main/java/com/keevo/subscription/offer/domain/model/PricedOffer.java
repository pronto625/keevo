package com.keevo.subscription.offer.domain.model;

/**
 * Story 21.10 — a base price and what the best discount makes of it.
 *
 * @param baseXaf      the catalogue price
 * @param reductionXaf the reduction of the applied discount, 0 when none
 * @param effectiveXaf {@code base − reduction}, never negative
 * @param discount     the discount applied, {@code null} when none
 */
public record PricedOffer(long baseXaf, long reductionXaf, long effectiveXaf, Discount discount) {

    /** The effective price per month of a cycle of {@code months} months, rounded half-up to the XAF. */
    public long perMonthXaf(int months) {
        return months <= 0 ? effectiveXaf : (effectiveXaf * 2 + months) / (2L * months);
    }
}
