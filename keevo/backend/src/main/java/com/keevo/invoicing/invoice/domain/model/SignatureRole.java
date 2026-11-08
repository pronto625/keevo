package com.keevo.invoicing.invoice.domain.model;

import java.util.Locale;
import java.util.Optional;

/** Story 20.7 — who signed a document: the client or the seller (Bref's two signature pads). */
public enum SignatureRole {
    CLIENT("Signature client"),
    SELLER("Signature vendeur");

    private final String label;

    SignatureRole(String label) {
        this.label = label;
    }

    /** French label printed under the signature. */
    public String label() {
        return label;
    }

    /** {@code client} / {@code seller} (case ignored); anything else → empty. */
    public static Optional<SignatureRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        for (SignatureRole r : values()) {
            if (r.name().equals(v)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
