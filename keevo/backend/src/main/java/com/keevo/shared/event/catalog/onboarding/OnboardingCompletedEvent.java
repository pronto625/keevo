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
 *
 * <p>{@code categoriesCreated}: template categories requested (12.6a — seeded by the catalog on
 * {@link TenantOnboardedEvent}); on the first (and only) onboarding of a tenant it equals the rows created.
 */
public record OnboardingCompletedEvent(
        String tenantId,
        SectorKind sectorType,
        String storeName,
        int categoriesCreated,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {}
