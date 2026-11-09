package com.keevo.payments.checkout.application.service;

import java.time.Duration;

/**
 * Story 21.1 — settings of the checkout ({@code keevo.payments.*}).
 *
 * @param initiatingTimeout an INITIATING request older than this is expired by the next checkout (lock recovery)
 * @param pendingTimeout    a PENDING request older than this is expired by the next checkout
 * @param callbackHash      secret path segment of the provider webhook ({@code MYCOOLPAY_CALLBACK_HASH}); blank =
 *                          every callback refused
 * @param maxCheckoutsPerHour checkouts a company may start per hour (each one prompts a phone)
 */
public record PaymentSettings(Duration initiatingTimeout, Duration pendingTimeout, String callbackHash,
                              int maxCheckoutsPerHour) {

    public PaymentSettings(Duration initiatingTimeout, Duration pendingTimeout, String callbackHash) {
        this(initiatingTimeout, pendingTimeout, callbackHash, 5);
    }

    @Override
    public String toString() { // never print the secret
        return "PaymentSettings[initiatingTimeout=" + initiatingTimeout + ", pendingTimeout=" + pendingTimeout + "]";
    }
}
