package com.keevo.crm.contact.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ClientUnarchivedEvent — domain event published after an archived client is restored
 * (sync UNARCHIVE_CLIENT).
 */
public record ClientUnarchivedEvent(
        UUID clientId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
