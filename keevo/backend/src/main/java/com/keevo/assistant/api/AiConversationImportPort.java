package com.keevo.assistant.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.6 — writes a Bref owner's AI assistant conversations into the imported company's {@code ai_conversations}
 * (the module's tables must exist), as they were in Bref: same ids, same JSON (the app's Bref shapes, stored opaque as
 * 21.4 does), original dates. Joins the caller's transaction; rows already present are left untouched. An invalid row
 * throws {@link IllegalArgumentException} and nothing is written.
 */
public interface AiConversationImportPort {

    /** The most conversations a user keeps (21.4): an import never writes more for one user. */
    int MAX_PER_USER = 200;

    /**
     * @param messages  JSON array text (the app's messages)
     * @param aiHistory JSON array text ({@code {role, content}} items)
     */
    record ImportedConversation(UUID id, UUID userId, String title, int messageCount, String messages,
                                String aiHistory, Instant createdAt, Instant updatedAt) {

        public ImportedConversation {
            Objects.requireNonNull(id, "id");
        }
    }

    /** @return the ids inserted by this call */
    Set<UUID> importConversations(String tenantSchema, List<ImportedConversation> conversations);
}
