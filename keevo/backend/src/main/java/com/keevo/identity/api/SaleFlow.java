package com.keevo.identity.api;

import java.util.Optional;

/**
 * The tenant's default sale flow (Story 19.12, FUS-FR17): where the owner lands to sell — the till ({@link #POS},
 * every existing Keevo tenant) or documents ({@link #DOCUMENT}, owners imported from Bref). Owner-editable. Stored in
 * {@code tenant_preferences.default_sale_flow}.
 */
public enum SaleFlow {
    POS("Caisse"),
    DOCUMENT("Documents");

    private final String label;

    SaleFlow(String label) {
        this.label = label;
    }

    /** French label (« Caisse » / « Documents »). */
    public String label() {
        return label;
    }

    /** The flow of an exact code; empty for anything else (null, other case, unknown). */
    public static Optional<SaleFlow> fromCode(String code) {
        if (code == null) return Optional.empty();
        for (SaleFlow flow : values()) {
            if (flow.name().equals(code)) return Optional.of(flow);
        }
        return Optional.empty();
    }
}
