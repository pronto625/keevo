package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.port.in.GetEffectiveTenantPreferencesUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link TenantPreferencesQueryPort} over the tenant-bound {@link TenantPreferencesRepository} port
 * (Story 12.2d); the given schema must be the current tenant context ({@link TenantContext#requireCurrent}).
 *
 * <p>Story 17.8 — every consumer gets the effective preferences ({@link GetEffectiveTenantPreferencesUseCase}: the
 * fixed defaults when {@code report-config} is DISABLED); the stored row is never modified.
 */
@Component
class TenantPreferencesQueryAdapter implements TenantPreferencesQueryPort {

    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final GetEffectiveTenantPreferencesUseCase effectivePreferences;

    TenantPreferencesQueryAdapter(TenantPreferencesRepository tenantPreferencesRepository,
                                  GetEffectiveTenantPreferencesUseCase effectivePreferences) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.effectivePreferences = effectivePreferences;
    }

    @Override
    public Optional<TenantPreferencesView> find(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return tenantPreferencesRepository.findByCurrentTenant()
                .map(p -> effectivePreferences.effective(tenantSchema, p).view());
    }
}
