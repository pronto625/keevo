package com.keevo.whatsappcloud.inbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.5 — one message of a WhatsApp conversation of the company number (Bref {@code whatsapp_chat_message}):
 * received ({@code IN}) or sent ({@code OUT}); {@code type} = Meta's (text, image, document, template…); {@code status}
 * of a sent message SENT → DELIVERED → READ, or FAILED; {@code readAt} of a received one once opened.
 */
public record ChatMessage(UUID id, String contactPhone, Direction direction, String type, String body,
                          String waMessageId, String status, Instant readAt, UUID sentBy, Instant createdAt,
                          UUID campaignId) {

    public enum Direction { IN, OUT }

    /** A message outside any campaign (Story 21.6 added {@code campaignId}). */
    public ChatMessage(UUID id, String contactPhone, Direction direction, String type, String body,
                       String waMessageId, String status, Instant readAt, UUID sentBy, Instant createdAt) {
        this(id, contactPhone, direction, type, body, waMessageId, status, readAt, sentBy, createdAt, null);
    }
}
