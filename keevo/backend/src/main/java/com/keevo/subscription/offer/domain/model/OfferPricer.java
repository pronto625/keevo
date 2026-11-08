package com.keevo.subscription.offer.domain.model;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;

/**
 * Story 21.10 — the pricing policy of the offer (pure, no I/O): <b>the best single discount wins</b>, discounts never
 * stack. Shared by the owner's offers listing, the super-admin preview and the quote 21.1/21.2 store with a payment, so
 * the price shown is the price charged.
 *
 * <ul>
 *   <li>Candidates: discounts live at {@code now} (not archived, {@code starts_at <= now < ends_at}) covering the plan
 *       and the cycle. The caller passes only the discounts the customer is entitled to (promotions, its company's,
 *       the code it typed).</li>
 *   <li>Reduction: {@code round-half-up(base × percent / 100)} or {@code min(amount, base)}.</li>
 *   <li>Best = the largest reduction; a tie goes to the promo code, then the company discount, then the promotion,
 *       then the one ending first (no end last).</li>
 *   <li>Effective price = {@code base − reduction} (≥ 0).</li>
 * </ul>
 *
 * <p>Complexity O(discounts) per price; the listing prices O(plans × cycles × discounts) over tens of rows.
 */
public final class OfferPricer {

    private static final Comparator<Instant> END_FIRST = Comparator.nullsLast(Comparator.naturalOrder());

    private OfferPricer() {
    }

    /** The base price of {@code planCode} × {@code cycleCode} with the best of {@code candidates} applied. */
    public static PricedOffer price(long base, String planCode, String cycleCode, Collection<Discount> candidates,
                                    Instant now) {
        Discount best = null;
        long bestReduction = 0;
        for (Discount d : candidates) {
            if (!d.liveAt(now) || !d.covers(planCode, cycleCode)) {
                continue;
            }
            long reduction = d.reductionOn(base);
            if (reduction <= 0) {
                continue;
            }
            if (best == null || reduction > bestReduction
                    || (reduction == bestReduction && beatsOnTie(d, best))) {
                best = d;
                bestReduction = reduction;
            }
        }
        return new PricedOffer(base, bestReduction, Math.max(0, base - bestReduction), best);
    }

    private static boolean beatsOnTie(Discount challenger, Discount holder) {
        int byScope = Integer.compare(challenger.scope().tiePriority(), holder.scope().tiePriority());
        if (byScope != 0) {
            return byScope < 0;
        }
        return END_FIRST.compare(challenger.endsAt(), holder.endsAt()) < 0;
    }
}
