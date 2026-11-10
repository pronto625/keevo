package com.keevo.assistant.conversation.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.4 — a saved assistant conversation of one user (Bref {@code ai_conversation}).
 *
 * @param messages     the messages shown in the chat, a JSON array written by the app (opaque here)
 * @param aiHistory    the history the app sends back with each question, a JSON array (opaque here)
 * @param messageCount user + assistant messages, as counted by the app (Bref's 20-message cap)
 */
public record AiConversation(UUID id, UUID userId, String title, int messageCount, String messages, String aiHistory,
                             Instant createdAt, Instant updatedAt) {
}
