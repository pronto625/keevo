package com.keevo.whatsappcloud.inbox.domain.model;

import java.time.Instant;

/**
 * Story 21.5 — what new messages change in one contact's conversation: the latest of them, how many unread received
 * ones they add, the latest received time ({@code null} when only sent ones) and the contact's profile name
 * ({@code null} = keep the stored one).
 */
public record ConversationDelta(String contactPhone, String contactName, String lastMessage, String lastType,
                                ChatMessage.Direction lastDirection, Instant lastAt, int unreadIncrement,
                                Instant lastInboundAt) {
}
