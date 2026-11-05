package com.keevo.identity.api;

/**
 * The app's navigation preset (Story 19.13, interface profile): where the user lands and how the tabs are ordered —
 * {@link #CAISSE} (the till first: every existing Keevo tenant) or {@link #DOCUMENT} (documents first: owners
 * imported from Bref). Derived from the tenant's default sale flow ({@link SaleFlow}), never stored.
 */
public enum NavigationPreset {
    CAISSE,
    DOCUMENT;

    /** The preset of a sale flow: {@code POS} → {@link #CAISSE}, {@code DOCUMENT} → {@link #DOCUMENT}; null → CAISSE. */
    public static NavigationPreset of(SaleFlow flow) {
        return flow == SaleFlow.DOCUMENT ? DOCUMENT : CAISSE;
    }
}
