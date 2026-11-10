package com.keevo.assistant.conversation.domain.port.out;

import com.keevo.assistant.conversation.domain.model.AiConversation;
import com.keevo.assistant.conversation.domain.model.AiConversationSummary;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.4 — the conversations of the current tenant ({@code ai_conversations} of its schema). Every method is
 * scoped by the owning user: another user's conversation is never read, changed or deleted.
 */
public interface AiConversationRepository {

    /** The user's conversations, most recent first, at most {@code limit}. */
    List<AiConversationSummary> list(UUID userId, int limit);

    long count(UUID userId);

    Optional<AiConversation> find(UUID userId, UUID id);

    void insert(AiConversation conversation);

    /**
     * Inserts unless the user already keeps {@code cap} conversations (serialised per user, so concurrent creations
     * never exceed it).
     *
     * @return {@code false} when the cap is reached (nothing inserted)
     */
    boolean insertUnderCap(AiConversation conversation, int cap);

    /**
     * Each {@code null} argument keeps the stored value.
     *
     * @return {@code true} when the conversation was found and updated
     */
    boolean update(UUID userId, UUID id, String messages, String aiHistory, Integer messageCount, String title);

    void delete(UUID userId, UUID id);

    int deleteAll(UUID userId, Collection<UUID> ids);
}
