package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link TenantPreferencesQueryPort} over the tenant-bound
 * {@link TenantPreferencesRepository} port (Story 12.2d); the given schema must be the current
 * tenant context ({@link TenantContext#requireCurrent}).
 */
@Component
class TenantPreferencesQueryAdapter implements TenantPreferencesQueryPort {

    private final TenantPreferencesRepository tenantPreferencesRepository;

    TenantPreferencesQueryAdapter(TenantPreferencesRepository tenantPreferencesRepository) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
    }

    @Override
    public Optional<TenantPreferencesView> find(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return tenantPreferencesRepository.findByCurrentTenant().map(TenantPreferencesQueryAdapter::view);
    }

    static TenantPreferencesView view(TenantPreferences p) {
        return new TenantPreferencesView(p.eodReportTime(), p.stockAlertEnabled(), p.eodReportEnabled(),
                p.eodReportChannel(), p.weeklyReportEnabled(), p.weeklyReportDay(), p.weeklyReportTime(),
                p.weeklyReportChannel(), p.inventoryReportEnabled(), p.inventoryReportChannel(),
                p.stockAlertChannel(), p.trendNotificationEnabled());
    }
}
