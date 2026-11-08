package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — outcome of an SMS or email send.
 *
 * @param delivered true when a provider accepted the message (HTTP 2xx, and not rejected)
 * @param provider  name of the provider that delivered it ({@code mboasms}, {@code infobip}, {@code resend}), or the
 *                  one whose outcome is uncertain; null when certainly not delivered
 * @param uncertain Story 20.8 — the request reached a provider but its answer was lost (read timeout): the message may
 *                  have been accepted. Never delivered, never counted; the caller must not retry automatically.
 */
public record DeliveryResult(boolean delivered, String provider, boolean uncertain) {

    private static final DeliveryResult NOT_DELIVERED = new DeliveryResult(false, null, false);

    /** Story 19.9 shape: a certain outcome. */
    public DeliveryResult(boolean delivered, String provider) {
        this(delivered, provider, false);
    }

    public static DeliveryResult notDelivered() {
        return NOT_DELIVERED;
    }

    public static DeliveryResult deliveredBy(String provider) {
        return new DeliveryResult(true, provider, false);
    }

    /** Story 20.8 — sent to {@code provider} but unanswered: maybe delivered. */
    public static DeliveryResult uncertain(String provider) {
        return new DeliveryResult(false, provider, true);
    }
}
