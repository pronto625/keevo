package com.keevo.subscription.offer.domain.model;

/** Story 21.10 — what a catalogue change journal line is about ({@code public.offer_change_log.entity}). */
public enum OfferEntity {
    PLAN("Plan"),
    CYCLE("Durée"),
    PRICE("Prix"),
    SETTINGS("Réglages"),
    DISCOUNT("Remise"),
    /** Story 21.2 — an App Store product mapping. */
    STORE_PRODUCT("Produit App Store"),
    /** Story 21.3 — a usage pack (SMS…). */
    USAGE_PACK("Pack");

    private final String label;

    OfferEntity(String label) {
        this.label = label;
    }

    /** French name. */
    public String label() {
        return label;
    }
}
