package com.keevo.messaging.notification.domain.model;

import java.time.Instant;

/**
 * Story 19.9b — an Expo ticket accepted by {@code /push/send}, waiting for its receipt: the device token it was sent
 * to and the tenant schema holding that token ({@code public.expo_push_tickets}).
 */
public record ExpoPushTicket(String ticketId, String token, String tenantSchema, Instant createdAt) {
}
