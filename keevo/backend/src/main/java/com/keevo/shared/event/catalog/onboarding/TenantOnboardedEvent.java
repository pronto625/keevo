package com.keevo.shared.event.catalog.onboarding;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TenantOnboardedEvent — the owner completed onboarding with a sector (Story 12.6a).
 *
 * <p>Published by identity's onboarding inside its transaction; the catalog seeds the sector template's root
 * categories from {@link #templateCategories()} in a synchronous {@code BEFORE_COMMIT} listener, so the
 * categories commit (or roll back) with the onboarding and are present on the first catalogue load — same
 * result as the former direct {@code CategoryCommandPort.seedTemplateCategories} call, without identity
 * depending on catalog.
 *
 * @param tenantId           tenant schema name
 * @param sectorType         chosen sector (null if none)
 * @param templateCategories the sector template's root category names, in template order (null / blank
 *                           names dropped, the others trimmed — a template typo must not break onboarding)
 * @param actorId            owner who completed the onboarding
 * @param occurredAt         when the onboarding completed
 */
public record TenantOnboardedEvent(
        String tenantId,
        SectorKind sectorType,
        List<String> templateCategories,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {

    /** The single place where template names are cleaned: null / blank names dropped, the others trimmed. */
    public TenantOnboardedEvent {
        templateCategories = templateCategories == null ? List.of() : templateCategories.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .toList();
    }
}
