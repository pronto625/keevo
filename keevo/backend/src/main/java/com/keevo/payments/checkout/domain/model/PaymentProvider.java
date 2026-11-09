package com.keevo.payments.checkout.domain.model;

/**
 * Story 21.1 — who collects the money: {@link #MYCOOLPAY} (real), {@link #SANDBOX} (simulated provider of tests and
 * local runs), {@link #NONE} (free offer, nothing to collect).
 */
public enum PaymentProvider {
    MYCOOLPAY, SANDBOX, NONE
}
