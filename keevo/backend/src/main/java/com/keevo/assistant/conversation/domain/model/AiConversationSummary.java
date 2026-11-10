package com.keevo.assistant.conversation.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Story 21.4 — a line of the conversation list (no message content). */
public record AiConversationSummary(UUID id, String title, int messageCount, Instant createdAt, Instant updatedAt) {
}
