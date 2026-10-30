package com.keevo.shared.event.catalog.onboarding;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Story 14.2 — the owner changed the company identity (designation and / or logo) shown on invoices and export
 * documents. {@code before} / {@code after} hold only the changed fields ({@code designation}, {@code logo});
 * {@code displayName} is the name now shown. Consumed by {@code AuditEventListener} (COMPANY_PROFILE_UPDATED).
 */
public record CompanyProfileUpdatedEvent(
        UUID tenantUuid,
        String displayName,
        Map<String, Object> before,
        Map<String, Object> after,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
