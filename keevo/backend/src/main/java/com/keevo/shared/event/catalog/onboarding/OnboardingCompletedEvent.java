package com.keevo.shared.event.catalog.onboarding;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * OnboardingCompletedEvent — Domain event published after successful onboarding completion.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}.
 *
 * <p>Pure Java record — immutable, no framework dependencies.
 */
public record OnboardingCompletedEvent(
        String tenantId,
        SectorKind sectorType,
        String storeName,
        int categoriesCreated,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {}
