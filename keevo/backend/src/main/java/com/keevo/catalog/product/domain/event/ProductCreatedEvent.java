package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductCreatedEvent — Domain event published after successful product creation.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}
 * and {@link com.keevo.messaging.notification.application.listener.ProductCreationNotificationListener}.
 *
 * <p>Pure Java record — immutable, no framework dependencies.
 * 
 * <p>Story 2.1 — Product CRUD with audit trail integration.
 * <p>Story 14.10 — added actorRole, actorName, storeName for employee-action notifications.
 */
public record ProductCreatedEvent(
        UUID productId,
        String productName,
        String productSku,
        String tenantId,
        UUID actorId,
        String actorRole,   // Story 14.10: "OWNER" or "EMPLOYEE"
        String actorName,   // Story 14.10: display name for notification body
        String storeName,   // Story 14.10: employee's assigned store name (nullable for OWNER)
        Instant occurredAt
) {}