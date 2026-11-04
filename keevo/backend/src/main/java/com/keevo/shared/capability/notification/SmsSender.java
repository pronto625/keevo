package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — sends an SMS through the configured providers, in their failover order ({@code keevo.sms.providers}).
 *
 * <p>Core contract: a consumer (OTP, invoice sending) depends on {@code shared} only, never on a provider. A platform
 * message (OTP, welcome) is sent here directly and never counted; a message sent on behalf of a tenant goes through
 * {@link TenantMessaging}, which counts it on the tenant's quota after the provider confirms.
 *
 * <p>Never throws on a provider failure: the result says whether one provider delivered.
 */
public interface SmsSender {

    /** False when no provider is configured ({@code keevo.sms.providers} empty): every send is not delivered. */
    boolean isEnabled();

    /**
     * Sends the message through the first provider that accepts it.
     *
     * @return delivered with the provider name, or {@link DeliveryResult#notDelivered()} (channel disabled, invalid
     *         phone, every provider failed)
     */
    DeliveryResult send(SmsMessage message);
}
