package com.keevo.payments.checkout.domain.model;

/**
 * Story 21.3 — what a payment request buys ({@code public.payment_requests.kind}): one checkout, webhook and settlement
 * path serves both.
 */
public enum PaymentKind {
    /** A plan for a billing cycle (21.1). */
    PLAN,
    /** A pack of a usage metric, e.g. SMS (21.3). */
    USAGE_PACK
}
