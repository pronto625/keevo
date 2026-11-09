package com.keevo.payments.checkout.domain.model;

import java.util.Set;

/**
 * Story 21.1 — state of a payment request ({@code public.payment_requests.status}). {@link #INITIATING} and
 * {@link #PENDING} are « in flight » (at most one per company); {@link #CONFIRMED} is terminal; a payment the provider
 * confirms is honoured from any other state (money received is never lost).
 */
public enum PaymentStatus {
    INITIATING("Paiement en cours de démarrage"),
    PENDING("En attente de validation"),
    CONFIRMED("Paiement confirmé"),
    FAILED("Paiement non abouti"),
    CANCELLED("Paiement annulé"),
    EXPIRED("Paiement expiré");

    /** The in-flight states (the « INITIATING lock » covers both). */
    public static final Set<PaymentStatus> IN_FLIGHT = Set.of(INITIATING, PENDING);

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    /** French label shown to the owner. */
    public String label() {
        return label;
    }
}
