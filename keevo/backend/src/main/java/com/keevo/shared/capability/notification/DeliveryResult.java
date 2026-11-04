package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — outcome of an SMS or email send.
 *
 * @param delivered true when a provider accepted the message (HTTP 2xx, and not rejected)
 * @param provider  name of the provider that delivered it ({@code mboasms}, {@code infobip}, {@code resend}); null
 *                  when not delivered
 */
public record DeliveryResult(boolean delivered, String provider) {

    private static final DeliveryResult NOT_DELIVERED = new DeliveryResult(false, null);

    public static DeliveryResult notDelivered() {
        return NOT_DELIVERED;
    }

    public static DeliveryResult deliveredBy(String provider) {
        return new DeliveryResult(true, provider);
    }
}
