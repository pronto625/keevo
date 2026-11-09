package com.keevo.shared.capability.entitlement;

/**
 * Story 21.3 — why a pack balance moved ({@code public.usage_credit_movements.source}).
 */
public enum UsagePackSource {
    /** A pack bought and paid by the owner (+). */
    PURCHASE("Pack acheté"),
    /** A unit consumed from the pack once the plan's quota was used up (−). */
    CONSUMPTION("Utilisé"),
    /** Units carried over from Bref (+). */
    MIGRATION("Repris de votre ancien compte");

    private final String label;

    UsagePackSource(String label) {
        this.label = label;
    }

    /** French label shown to the owner. */
    public String label() {
        return label;
    }
}
