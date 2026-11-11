package com.keevo.crm.api;

import java.time.Instant;

/**
 * Story 21.11 — purchase-behaviour filter of a marketing audience (Bref campaign {@code targetingCriteria}), with
 * absolute cut-offs. Every bound is optional ({@code null} = not set) and inclusive. The purchases of a phone number
 * are the revenue documents of every client record with that number: COMPLETED sales and counted invoices (a sale
 * bridged to an invoice counted once), credit notes deducted from the total; when the audience targets stores, only
 * the documents of those stores.
 *
 * @param noPurchaseSince no purchase at or after this instant (never-buyers included) — Bref {@code inactiveForDays}
 * @param purchasedSince  a purchase at or after this instant — Bref {@code purchasedWithinDays}
 * @param spentMin        net total of the purchases ≥ (FCFA)
 * @param spentMax        net total of the purchases ≤ (FCFA)
 * @param countMin        number of purchases ≥ (sales + invoices)
 * @param countMax        number of purchases ≤
 */
public record PurchaseFilter(Instant noPurchaseSince, Instant purchasedSince, Long spentMin, Long spentMax,
                             Integer countMin, Integer countMax) {

    /** No criterion: the audience of Story 21.6. */
    public static final PurchaseFilter NONE = new PurchaseFilter(null, null, null, null, null, null);

    public boolean isEmpty() {
        return noPurchaseSince == null && purchasedSince == null && spentMin == null && spentMax == null
                && countMin == null && countMax == null;
    }

    public static boolean isEmpty(PurchaseFilter filter) {
        return filter == null || filter.isEmpty();
    }
}
