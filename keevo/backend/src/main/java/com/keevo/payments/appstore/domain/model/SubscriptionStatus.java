package com.keevo.payments.appstore.domain.model;

/** Story 21.2 — state of an App Store subscription ({@code public.external_subscriptions.status}), French labels. */
public enum SubscriptionStatus {
    ACTIVE("Actif"),
    CANCELLED("Renouvellement automatique désactivé"),
    BILLING_ISSUE("Problème de paiement"),
    EXPIRED("Terminé"),
    REVOKED("Remboursé"),
    TRANSFERRED("Transféré vers un autre compte");

    private final String label;

    SubscriptionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The subscription still gives access (until its expiry). */
    public boolean live() {
        return this == ACTIVE || this == CANCELLED || this == BILLING_ISSUE;
    }
}
