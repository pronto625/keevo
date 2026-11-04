package com.keevo.messaging.channel.domain.model;

/** Story 19.9 — what one provider call ended with. */
public enum SendOutcome {
    /** The provider accepted the message (HTTP 2xx, not rejected). */
    DELIVERED,
    /** Certainly not sent (missing keys, connect failure, non-2xx, rejection): the chain tries the next provider. */
    FAILED,
    /**
     * Sent but unanswered (read timeout): the provider may have accepted it. The chain stops — trying the next one
     * could deliver the message twice — and reports not delivered, so nothing is counted.
     */
    UNKNOWN
}
