package com.keevo.shared.event.catalog.onboarding;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * LeaderboardVisibilityChangedEvent — the owner shared the seller leaderboard with employees, or hid it again
 * (Story 17.2, module {@code advanced-reports}).
 *
 * <p>Published only for a real change (same value → no event). Consumed by
 * {@link com.keevo.shared.infrastructure.web.AuditEventListener}: « Classement partagé avec les employés » /
 * « Classement masqué aux employés ».
 *
 * <p>Pure Java — NO Spring/framework imports.
 *
 * @param visibleToEmployees the new setting
 */
public record LeaderboardVisibilityChangedEvent(
        UUID preferencesId,
        boolean visibleToEmployees,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
