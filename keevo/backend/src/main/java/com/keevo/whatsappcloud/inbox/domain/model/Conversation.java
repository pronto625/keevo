package com.keevo.whatsappcloud.inbox.domain.model;

import java.time.Instant;

/**
 * Story 21.5 — one contact's conversation, maintained on write (last message, unread count, time of the contact's last
 * message that opens the 24-hour reply window).
 */
public record Conversation(String contactPhone, String contactName, String lastMessage, String lastType,
                           ChatMessage.Direction lastDirection, Instant lastAt, int unreadCount,
                           Instant lastInboundAt) {
}
