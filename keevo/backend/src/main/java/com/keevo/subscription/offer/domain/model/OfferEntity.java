package com.keevo.subscription.offer.domain.model;

/** Story 21.10 — what a catalogue change journal line is about ({@code public.offer_change_log.entity}). */
public enum OfferEntity {
    PLAN("Plan"),
    CYCLE("Durée"),
    PRICE("Prix"),
    SETTINGS("Réglages"),
    DISCOUNT("Remise");

    private final String label;

    OfferEntity(String label) {
        this.label = label;
    }

    /** French name. */
    public String label() {
        return label;
    }
}
