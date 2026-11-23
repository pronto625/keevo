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
 *
 * <p>Story 22.19 — {@code skipSaleFlowRules}: true for the app's sign-up steps ({@code /account/company/setup/complete}),
 * which do not ask for a sale flow: the initial module activation rules of a sale flow (19.13: POS turns invoicing off,
 * DOCUMENT turns the till off) are then not applied, every module of the plan stays on. False (the default, and the
 * value of payloads stored before 22.19) for {@code /onboarding/complete}.
 */
public record OnboardingCompletedEvent(
        String tenantId,
        SectorKind sectorType,
        String storeName,
        int categoriesCreated,
        UUID actorId,
        Instant occurredAt,
        boolean skipSaleFlowRules
) implements DomainEvent {

    /** Onboarding through {@code /onboarding/complete}: the 19.13 rules apply (the behaviour before 22.19). */
    public OnboardingCompletedEvent(String tenantId, SectorKind sectorType, String storeName, int categoriesCreated,
                                    UUID actorId, Instant occurredAt) {
        this(tenantId, sectorType, storeName, categoriesCreated, actorId, occurredAt, false);
    }
}
