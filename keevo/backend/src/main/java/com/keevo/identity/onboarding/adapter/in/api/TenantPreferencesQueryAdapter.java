package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link TenantPreferencesQueryPort} over the tenant-bound
 * {@link TenantPreferencesRepository} port (Story 12.2d); the given schema must be the current
 * tenant context ({@link TenantContext#requireCurrent}).
 *
 * <p>Story 17.8 — effective preferences: when the paid module {@value #REPORT_CONFIG} is DISABLED for the tenant
 * (Free, grace over), every consumer gets {@link TenantPreferencesView#fixedDefaults()} instead of the stored row,
 * which is kept untouched (it comes back if the tenant returns to Premium). ENABLED and READ_ONLY (grace) keep the
 * stored values; an unreadable module state also keeps them (fail-open, WARN logged).
 *
 * <p>{@link #effective} is the identity-internal entry point of {@code GET /tenant/preferences}: one state read
 * gives both the values and {@code reportConfigEditable} (ENABLED only; unreadable state → stored values, not
 * editable).
 */
@Component
public class TenantPreferencesQueryAdapter implements TenantPreferencesQueryPort {

    /** The paid module that makes the report / alert preferences configurable. */
    public static final String REPORT_CONFIG = "report-config";

    /** The effective preferences of a stored row and whether the tenant may change them, from one state read. */
    public record Effective(TenantPreferencesView view, boolean reportConfigEditable) {}

    private static final Logger log = LoggerFactory.getLogger(TenantPreferencesQueryAdapter.class);

    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final EntitlementService entitlementService;

    public TenantPreferencesQueryAdapter(TenantPreferencesRepository tenantPreferencesRepository,
                                  EntitlementService entitlementService) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.entitlementService = entitlementService;
    }

    @Override
    public Optional<TenantPreferencesView> find(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return tenantPreferencesRepository.findByCurrentTenant().map(p -> effective(tenantSchema, p).view());
    }

    /**
     * The single rule: DISABLED → {@link TenantPreferencesView#fixedDefaults()}, otherwise the stored values;
     * editable only when ENABLED. An unreadable state (WARN logged) → stored values, not editable.
     */
    public Effective effective(String tenantSchema, TenantPreferences stored) {
        ModuleState state;
        try {
            state = entitlementService.state(tenantSchema, REPORT_CONFIG);
        } catch (RuntimeException e) {
            log.warn("report-config state unreadable for tenant={}, stored report preferences kept: {}",
                    tenantSchema, e.toString());
            return new Effective(view(stored), false);
        }
        return new Effective(state == ModuleState.DISABLED ? TenantPreferencesView.fixedDefaults() : view(stored),
                state == ModuleState.ENABLED);
    }

    static TenantPreferencesView view(TenantPreferences p) {
        return new TenantPreferencesView(p.eodReportTime(), p.stockAlertEnabled(), p.eodReportEnabled(),
                p.eodReportChannel(), p.weeklyReportEnabled(), p.weeklyReportDay(), p.weeklyReportTime(),
                p.weeklyReportChannel(), p.inventoryReportEnabled(), p.inventoryReportChannel(),
                p.stockAlertChannel(), p.trendNotificationEnabled());
    }
}
