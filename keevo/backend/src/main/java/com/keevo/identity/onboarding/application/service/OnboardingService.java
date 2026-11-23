package com.keevo.identity.onboarding.application.service;

import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.identity.onboarding.application.factory.SectorTemplateFactory;
import com.keevo.shared.event.catalog.onboarding.OnboardingCompletedEvent;
import com.keevo.shared.event.catalog.onboarding.TenantOnboardedEvent;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingUseCase;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.identity.onboarding.domain.port.in.SetupSectorOnboardingUseCase;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.api.SaleFlow;
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
 *   <li>Category bulk-seeding in tenant schema — by the catalog, on {@link TenantOnboardedEvent} (12.6a)</li>
 *   <li>Store name update</li>
 *   <li>Tenant preferences seeded with defaults</li>
 *   <li>Domain event publication (Observer — AuditEventListener)</li>
 * </ol>
 *
 * <p>All DB operations execute in a single {@code @Transactional} boundary — the catalog's category seeding
 * included (synchronous {@code BEFORE_COMMIT} listener). No partial state upon failure.
 */
@Service
public class OnboardingService implements CompleteOnboardingUseCase, SetupSectorOnboardingUseCase {

    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final OnboardingStoreRepository storeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SectorTemplateFactory sectorTemplateFactory;

    public OnboardingService(
            TenantPreferencesRepository tenantPreferencesRepository,
            OnboardingStoreRepository storeRepository,
            ApplicationEventPublisher eventPublisher,
            SectorTemplateFactory sectorTemplateFactory) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.storeRepository = storeRepository;
        this.eventPublisher = eventPublisher;
        this.sectorTemplateFactory = sectorTemplateFactory;
    }

    @Override
    @Transactional
    public OnboardingResult complete(CompleteOnboardingCommand command) {
        String tenantId = TenantContext.getCurrentTenant();

        // 0. Idempotency guard — reject if onboarding already completed for this tenant (Story 22.19: under the
        //    tenant's preferences lock, so a concurrent first settings write cannot add a second row)
        tenantPreferencesRepository.lockForCreation();
        if (tenantPreferencesRepository.hasOnboardingCompleted()) {
            throw new DomainException(ErrorCode.ONBOARDING_ALREADY_COMPLETED,
                    "Onboarding already completed for tenant " + tenantId);
        }

        // Story 22.19 — a sector-less row (a settings write came first) is completed, never a second row
        TenantPreferences sectorLess = tenantPreferencesRepository.findByCurrentTenant()
                .filter(p -> p.sectorType() == null).orElse(null);
        int seededCategories = onboard(tenantId, command.sectorType(), command.storeName(), command.actorId(),
                command.defaultSaleFlow(), true, sectorLess);

        return new OnboardingResult(
            tenantId,
            command.sectorType(),
            command.storeName(),
            seededCategories
        );
    }

    /**
     * Story 22.19 (D2) — the sign-up steps' sector onboarding, once: a no-op when the preferences carry a sector. A
     * row without a sector (created from the defaults by a settings write before the steps ended) gets the sector
     * and the template categories instead of a second row.
     */
    @Override
    @Transactional
    public boolean onboardIfAbsent(SectorType sectorType, String companyName, UUID actorId) {
        tenantPreferencesRepository.lockForCreation(); // Story 22.19 — one row even under concurrent calls
        java.util.Optional<TenantPreferences> existing = tenantPreferencesRepository.findByCurrentTenant();
        if (existing.isPresent() && existing.get().sectorType() != null) {
            return false;
        }
        onboard(TenantContext.getCurrentTenant(), sectorType == null ? SectorType.OTHER : sectorType, companyName,
                actorId, null, false, existing.orElse(null));
        return true;
    }

    /**
     * The onboarding core shared by {@code /onboarding/complete} and the sign-up steps' end.
     *
     * @param renameStore whether the store (and a company still named by its code) takes {@code storeName}
     * @param existing    the sector-less preferences row to complete, {@code null} = insert a new row
     * @return the number of template categories requested
     */
    private int onboard(String tenantId, SectorType sectorType, String storeName, UUID actorId, SaleFlow saleFlow,
                        boolean renameStore, TenantPreferences existing) {
        // 1. Resolve sector template strategy
        SectorTemplateStrategy strategy = sectorTemplateFactory.create(sectorType);

        // 2-3. The template's root categories (active, non-custom) are seeded by the catalog on
        // TenantOnboardedEvent (12.6a), before this transaction commits. The value reported is the number of
        // template categories requested (the event drops null / blank names); onboarding runs once per tenant
        // (guarded above), so on this first onboarding it equals the rows created.
        Instant now = Instant.now();
        TenantOnboardedEvent onboarded = new TenantOnboardedEvent(
            tenantId,
            sectorType == null ? null : SectorKind.valueOf(sectorType.name()),
            strategy.getDefaultCategories(),
            actorId,
            now
        );
        int seededCategories = onboarded.templateCategories().size(); // template categories requested
        eventPublisher.publishEvent(onboarded);

        // 4. Update store name from default "Ma Boutique" to merchant's choice
        if (renameStore) {
            storeRepository.updateStoreName(storeName);
            // 4b. The company takes the shop name too (it still carries its technical code)
            storeRepository.nameCompanyIfStillCode(storeName);
        }

        // 5. Seed tenant preferences with defaults (AC8 + Story 7.5 fields) — or give the sector-less row its sector
        if (existing != null) {
            tenantPreferencesRepository.update(existing.withSectorType(sectorType));
            if (saleFlow != null) { // Story 19.13 — the flow chosen at sign-up, on the completed row too
                tenantPreferencesRepository.updateDefaultSaleFlow(existing.id(), saleFlow);
            }
        } else {
            savePreferences(sectorType, saleFlow, now);
        }

        // 6. Publish domain event (Observer → AuditEventListener)
        eventPublisher.publishEvent(new OnboardingCompletedEvent(
            tenantId,
            sectorType == null ? null : SectorKind.valueOf(sectorType.name()),
            storeName,
            seededCategories,
            actorId,
            now
        ));

        return seededCategories;
    }

    private void savePreferences(SectorType sectorType, SaleFlow saleFlow, Instant now) {
        TenantPreferences prefs = TenantPreferences.defaults(sectorType, now);
        // Story 19.13 — the sale flow chosen at sign-up, saved before OnboardingCompletedEvent (whose after-commit
        // listener applies the initial module activation rules of this flow and sector)
        if (saleFlow != null) {
            prefs = prefs.withDefaultSaleFlow(saleFlow);
        }
        tenantPreferencesRepository.save(prefs);
    }
}
