package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateLeaderboardVisibilityCommand;
import com.keevo.identity.onboarding.domain.port.in.UpdateLeaderboardVisibilityUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
        TenantPreferences existing = preferencesRepository.findByCurrentTenant()
                .orElseThrow(() -> new DomainException(ErrorCode.PREFERENCES_NOT_FOUND));
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
