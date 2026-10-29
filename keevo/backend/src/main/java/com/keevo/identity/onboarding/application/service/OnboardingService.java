package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.application.factory.SectorTemplateFactory;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingUseCase;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.catalog.api.CategoryCommandPort;
import com.keevo.identity.onboarding.domain.port.out.OnboardingStoreRepository;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.identity.onboarding.domain.service.SectorTemplateStrategy;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * OnboardingService — Application service / Façade pattern orchestrator for onboarding completion.
 *
 * <p>Orchestrates:
 * <ol>
 *   <li>Sector template resolution (Strategy + Factory)</li>
 *   <li>Category bulk-seeding in tenant schema</li>
 *   <li>Store name update</li>
 *   <li>Tenant preferences seeded with defaults</li>
 *   <li>Domain event publication (Observer — AuditEventListener)</li>
 * </ol>
 *
 * <p>All DB operations execute in a single {@code @Transactional} boundary.
 * No partial state upon failure.
 */
@Service
public class OnboardingService implements CompleteOnboardingUseCase {

    private final CategoryCommandPort categoryCommand; // 12.2a: catalog public API
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final OnboardingStoreRepository storeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SectorTemplateFactory sectorTemplateFactory;

    public OnboardingService(
            CategoryCommandPort categoryCommand,
            TenantPreferencesRepository tenantPreferencesRepository,
            OnboardingStoreRepository storeRepository,
            ApplicationEventPublisher eventPublisher,
            SectorTemplateFactory sectorTemplateFactory) {
        this.categoryCommand = categoryCommand;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.storeRepository = storeRepository;
        this.eventPublisher = eventPublisher;
        this.sectorTemplateFactory = sectorTemplateFactory;
    }

    @Override
    @Transactional
    public OnboardingResult complete(CompleteOnboardingCommand command) {
        String tenantId = TenantContext.getCurrentTenant();

        // 0. Idempotency guard — reject if onboarding already completed for this tenant
        if (tenantPreferencesRepository.hasOnboardingCompleted()) {
            throw new DomainException(ErrorCode.ONBOARDING_ALREADY_COMPLETED,
                    "Onboarding already completed for tenant " + tenantId);
        }

        // 1. Resolve sector template strategy
        SectorTemplateStrategy strategy = sectorTemplateFactory.create(command.sectorType());

        // 2-3. Seed the template's root categories (active, non-custom) through the catalog API
        Instant now = Instant.now();
        int seededCategories = categoryCommand.seedTemplateCategories(TenantContext.getCurrentTenant(), strategy.getDefaultCategories());

        // 4. Update store name from default "Ma Boutique" to merchant's choice
        storeRepository.updateStoreName(command.storeName());
        // 4b. The company takes the shop name too (it still carries its technical code)
        storeRepository.nameCompanyIfStillCode(command.storeName());

        // 5. Seed tenant preferences with defaults (AC8 + Story 7.5 fields)
        TenantPreferences prefs = TenantPreferences.withDefaults(
            UUID.randomUUID(),
            command.sectorType(),
            "20:00:00",     // default end-of-day report time
            true,           // default stock alerts enabled
            now
        );
        tenantPreferencesRepository.save(prefs);

        // 6. Publish domain event (Observer → AuditEventListener)
        eventPublisher.publishEvent(new OnboardingCompletedEvent(
            tenantId,
            command.sectorType(),
            command.storeName(),
            seededCategories,
            command.actorId(),
            now
        ));

        return new OnboardingResult(
            tenantId,
            command.sectorType(),
            command.storeName(),
            seededCategories
        );
    }
}
