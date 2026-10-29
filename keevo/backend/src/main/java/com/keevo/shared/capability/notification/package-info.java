/**
 * Notification capability of the core (Story 12.6a, spec-socle §6): WhatsApp delivery
 * ({@link com.keevo.shared.capability.notification.WhatsAppSender}) and push notifications
 * ({@link com.keevo.shared.capability.notification.PushNotifier}, with its
 * {@link com.keevo.shared.capability.notification.NotificationPayload} /
 * {@link com.keevo.shared.capability.notification.PushSendResult} records).
 *
 * <p>The contracts live in the core so that a module sending a message depends on {@code shared} only, never
 * on the provider: the implementations ({@code FcmNotificationAdapter}, {@code LoggingNotificationAdapter},
 * the WhatsApp adapters) stay internal to {@code messaging}. Formerly {@code messaging.api.WhatsAppPort} /
 * {@code NotificationPort} — moved to break the module cycles messaging formed as both provider and consumer.
 */
package com.keevo.shared.capability.notification;
