package com.keevo.subscription.offer.domain.model;

/**
 * Story 21.10 — who a discount is for (a concept, not offer content). The order of {@link #tiePriority()} breaks a tie
 * between two equal reductions: the code the owner typed first, then the company's own discount, then the promotion.
 */
public enum DiscountScope {

    /** Dated promotion for every company (an end date is required). */
    PROMOTION("Promotion", 2),
    /** Discount granted to one company. */
    TENANT("Remise entreprise", 1),
    /** Code typed by the owner. */
    PROMO_CODE("Code promo", 0);

    private final String label;
    private final int tiePriority;

    DiscountScope(String label, int tiePriority) {
        this.label = label;
        this.tiePriority = tiePriority;
    }

    /** French name. */
    public String label() {
        return label;
    }

    /** Lower wins a tie. */
    public int tiePriority() {
        return tiePriority;
    }
}
