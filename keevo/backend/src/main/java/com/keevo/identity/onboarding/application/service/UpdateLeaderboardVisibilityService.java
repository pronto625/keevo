package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateLeaderboardVisibilityCommand;
import com.keevo.identity.onboarding.domain.port.in.UpdateLeaderboardVisibilityUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.event.catalog.onboarding.LeaderboardVisibilityChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * UpdateLeaderboardVisibilityService — Story 17.2: writes the « Afficher le classement aux employés » setting.
 * Only {@code leaderboard_visible_to_employees} changes; every report / alert field keeps its stored value.
 * Same value → no write, no audit.
 */
@Service
@Transactional
public class UpdateLeaderboardVisibilityService implements UpdateLeaderboardVisibilityUseCase {

    private final TenantPreferencesRepository preferencesRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateLeaderboardVisibilityService(TenantPreferencesRepository preferencesRepository,
                                              ApplicationEventPublisher eventPublisher) {
        this.preferencesRepository = preferencesRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TenantPreferences update(UpdateLeaderboardVisibilityCommand command) {
        preferencesRepository.lockForCreation(); // Story 22.19 — one row even under concurrent first writes
        TenantPreferences existing = preferencesRepository.findByCurrentTenant()
                // Story 22.19 (D2) — before the sector onboarding the GET returns the defaults without a row: the first
                // write creates it from those defaults
                .orElseGet(() -> preferencesRepository.save(TenantPreferences.defaults(null, java.time.Instant.now())));
        if (existing.leaderboardVisibleToEmployees() == command.visibleToEmployees()) {
            return existing;
        }
        TenantPreferences saved = preferencesRepository.update(
                existing.withLeaderboardVisibleToEmployees(command.visibleToEmployees()));
        eventPublisher.publishEvent(new LeaderboardVisibilityChangedEvent(
                existing.id(), command.visibleToEmployees(), CurrentActor.actorId(),
                TenantContext.getCurrentTenant(), Instant.now()));
        return saved;
    }
}
