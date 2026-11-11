package com.keevo.whatsappcloud.inbox.domain.port.out;

import com.keevo.whatsappcloud.inbox.domain.model.ChatMessage;
import com.keevo.whatsappcloud.inbox.domain.model.Conversation;
import com.keevo.whatsappcloud.inbox.domain.model.ConversationDelta;
import com.keevo.whatsappcloud.inbox.domain.model.StatusChange;
import com.keevo.whatsappcloud.inbox.domain.model.StatusUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Story 21.5 — the company's WhatsApp inbox (module tables {@code whatsapp_messages} / {@code whatsapp_conversations}
 * of the tenant schema). Every method names the tenant schema explicitly (the webhook writes for several companies in
 * one request).
 */
public interface WhatsAppInboxRepository {

    /**
     * Story 21.6 — the key naming the platform number's inbox ({@code public.platform_whatsapp_*}) instead of a tenant
     * schema: every method below accepts it.
     */
    String PLATFORM = "platform";

    /**
     * Inserts the messages in one batch, skipping those already stored (Meta's id unique).
     *
     * @return per message, whether it was inserted now (false = delivered again)
     */
    boolean[] insertAll(String schema, List<ChatMessage> messages);

    /** Applies the conversation deltas in one batch (upsert per contact, atomic increments). */
    void upsertConversations(String schema, List<ConversationDelta> deltas);

    /**
     * Applies delivery statuses in one set-based statement, never going back.
     *
     * @return one change per message whose status moved (Story 21.6: with its campaign, {@code null} outside one)
     */
    List<StatusChange> applyStatuses(String schema, List<StatusUpdate> updates);

    /** The conversations, most recent first. */
    List<Conversation> conversations(String schema, int limit);

    Optional<Conversation> conversation(String schema, String contactPhone);

    /**
     * A conversation's messages, newest first; when {@code before} is given, those strictly before the keyset
     * ({@code before}, {@code beforeId}) — {@code beforeId} {@code null} = every message at {@code before} excluded.
     */
    List<ChatMessage> thread(String schema, String contactPhone, Instant before, java.util.UUID beforeId, int limit);

    /**
     * Marks the received messages of a conversation read and sets its unread count from the messages still unread
     * (one transaction: never drifts).
     */
    void markRead(String schema, String contactPhone, Instant now);

    /** Received messages not read yet, every conversation. */
    long unreadCount(String schema);
}
