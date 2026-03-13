package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductCreatedProgressivelyEvent — Emitted when a DRAFT product is created on-the-fly.
 *
 * <p>Used by DraftProductNotificationListener (messaging module) to:
 * – persist a DraftPendingValidation record (drives the in-app badge count)
 * – notify owners if the actor is an EMPLOYEE (skip if OWNER, to avoid self-spam)
 *
 * <p>actorName and actorRole are required to personalise the notification message
 * and decide whether to alert the owner.
 */
public record ProductCreatedProgressivelyEvent(
        UUID    productId,
        String  productName,   // for notification body
        UUID    actorId,
        String  actorName,     // "Loïc a créé…" — display name resolved by use case
        String  actorRole,     // "OWNER" or "EMPLOYEE" — controls notification dispatch
        String  tenantId,
        String  initialStatus, // "DRAFT"
        Instant occurredAt
) {}
