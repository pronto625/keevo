package com.keevo.payments.checkout.domain.model;

/**
 * Story 21.1 — what the owner must do next: {@link #OTP} type the code received by SMS ({@code /authorize}),
 * {@link #USSD} validate on the phone (dial the USSD code when one is given), {@link #NONE} nothing (finished).
 */
public enum NextStep {
    OTP, USSD, NONE
}
