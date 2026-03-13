package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CsvImportCompletedEvent — Emitted ONCE after the import loop completes.
 *
 * <p>Used by DraftProductNotificationListener to send a single BATCHED notification
 * instead of N individual ones (one per imported product). This prevents notification spam.
 *
 * <p>If actorRole == "OWNER": no notification is sent (owner is already aware).
 */
public record CsvImportCompletedEvent(
        int     importedCount,
        String  tenantId,
        UUID    actorId,
        String  actorName,  // display name — for notification body
        String  actorRole,  // "OWNER" or "EMPLOYEE"
        Instant occurredAt
) {}
