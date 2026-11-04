/**
 * Notification capability of the core (Story 12.6a, spec-socle §6): WhatsApp delivery
 * ({@link com.keevo.shared.capability.notification.WhatsAppSender}) and push notifications
 * ({@link com.keevo.shared.capability.notification.PushNotifier}, with its
 * {@link com.keevo.shared.capability.notification.NotificationPayload} /
 * {@link com.keevo.shared.capability.notification.PushSendResult} records). Story 19.9 adds SMS and email
 * ({@link com.keevo.shared.capability.notification.SmsSender}, {@link com.keevo.shared.capability.notification.EmailSender},
 * with provider failover) and the metered tenant sends
 * ({@link com.keevo.shared.capability.notification.TenantMessaging}).
 *
 * <p>The contracts live in the core so that a module sending a message depends on {@code shared} only, never
 * on the provider: the implementations ({@code FcmNotificationAdapter}, {@code LoggingNotificationAdapter},
 * the WhatsApp adapters) stay internal to {@code messaging}. Formerly {@code messaging.api.WhatsAppPort} /
 * {@code NotificationPort} — moved to break the module cycles messaging formed as both provider and consumer.
 */
package com.keevo.shared.capability.notification;
