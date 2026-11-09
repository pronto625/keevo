package com.keevo.payments.appstore.domain.model;

/**
 * Story 21.2 — what the processing of a RevenueCat event did ({@code public.revenuecat_webhook_event.outcome};
 * {@link #DUPLICATE} is never stored: the event was already there).
 */
public enum EventOutcome {
    /** The subscription and the plan followed the event. */
    APPLIED,
    /** Already processed (same event id). */
    DUPLICATE,
    /** Older than the last event processed for the same subscription. */
    STALE,
    /** Test purchase (sandbox) refused in this environment. */
    IGNORED_SANDBOX,
    /** Another store than the App Store. */
    IGNORED_STORE,
    /** An event type with no effect here. */
    IGNORED_TYPE,
    /** The RevenueCat customer is no Keevo owner (still on api-bref, anonymous id). */
    UNKNOWN_USER,
    /** The owner has several companies and none is designated: the owner's sync binds it later. */
    UNRESOLVED,
    /** The product is not mapped in the offer catalogue. */
    UNKNOWN_PRODUCT,
    /** RevenueCat dashboard test event. */
    TEST
}
