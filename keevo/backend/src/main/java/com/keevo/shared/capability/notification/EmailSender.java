package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — sends an email through the configured providers, in their failover order
 * ({@code keevo.email.providers}). Same contract as {@link SmsSender}: never throws on a provider failure; tenant
 * sends go through {@link TenantMessaging}.
 */
public interface EmailSender {

    /** False when no provider is configured ({@code keevo.email.providers} empty): every send is not delivered. */
    boolean isEnabled();

    /** Sends the message through the first provider that accepts it. */
    DeliveryResult send(EmailMessage message);
}
