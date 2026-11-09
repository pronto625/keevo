package com.keevo.payments.credit.domain.model;

/**
 * Story 21.3 — why the account credit of a company moved ({@code public.tenant_credit_movements.kind}).
 */
public enum CreditMovementKind {
    /** Added or removed by the support (super-admin), with a reason (±). */
    ADMIN_ADJUSTMENT("Ajustement par le support"),
    /** Reserved for a payment when it starts (−). */
    PAYMENT_USE("Utilisé pour un paiement"),
    /** Given back: the payment did not go through (+). */
    PAYMENT_RELEASE("Rendu : paiement non abouti"),
    /** A payment received after it was cancelled or expired, when the credit no longer covered the rest (+). */
    PAYMENT_TO_CREDIT("Paiement reçu ajouté au crédit"),
    /** Carried over from Bref (+). */
    MIGRATION("Repris de votre ancien compte");

    private final String label;

    CreditMovementKind(String label) {
        this.label = label;
    }

    /** French label shown to people. */
    public String label() {
        return label;
    }
}
