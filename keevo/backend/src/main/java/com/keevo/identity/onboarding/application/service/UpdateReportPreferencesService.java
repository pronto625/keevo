package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.PreferenceUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesCommand;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * UpdateReportPreferencesService — Applies validated report preferences to the current tenant.
 *
 * <p>Publishes a {@link PreferenceUpdatedEvent} so the auto-scheduler can adapt
 * its per-tenant trigger time without a circular dependency.
 */
@Service
@Transactional
public class UpdateReportPreferencesService implements UpdateReportPreferencesUseCase {

    private final TenantPreferencesRepository preferencesRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateReportPreferencesService(TenantPreferencesRepository preferencesRepository,
                                          ApplicationEventPublisher eventPublisher) {
        this.preferencesRepository = preferencesRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TenantPreferences update(UpdateReportPreferencesCommand command) {
        TenantPreferences existing = preferencesRepository.findByCurrentTenant()
                .orElseThrow(() -> new DomainException(ErrorCode.PREFERENCES_NOT_FOUND));

        String newEodTime = command.eodReportTime() != null
                ? command.eodReportTime()
                : existing.eodReportTime();

        TenantPreferences updated = new TenantPreferences(
            existing.id(),
            existing.sectorType(),
            newEodTime,
            command.stockAlertEnabled(),
            existing.createdAt(),
            command.eodReportEnabled(),
            command.eodReportChannel(),
            command.weeklyReportEnabled(),
            command.weeklyReportDay(),
            command.weeklyReportTime(),
            command.weeklyReportChannel(),
            command.inventoryReportEnabled(),
            command.inventoryReportChannel(),
            command.stockAlertChannel()
        );

        TenantPreferences saved = preferencesRepository.update(updated);

        eventPublisher.publishEvent(
                new PreferenceUpdatedEvent(existing.id().toString(), newEodTime));

        return saved;
    }
}
