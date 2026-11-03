package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.GetEffectiveTenantPreferencesUseCase;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * EffectiveTenantPreferencesService — Story 17.8: the single « effective preferences » rule, used by the
 * {@code identity :: api} preferences port (every report / alert job) and by {@code GET /tenant/preferences}.
 * One module-state read gives both the values and {@code reportConfigEditable}, so they never disagree.
 */
@Service
public class EffectiveTenantPreferencesService implements GetEffectiveTenantPreferencesUseCase {

    private static final Logger log = LoggerFactory.getLogger(EffectiveTenantPreferencesService.class);

    private final EntitlementService entitlementService;

    public EffectiveTenantPreferencesService(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @Override
    public EffectivePreferences effective(String tenantSchema, TenantPreferences stored) {
        ModuleState state;
        try {
            state = entitlementService.state(tenantSchema, REPORT_CONFIG);
        } catch (RuntimeException e) {
            log.warn("report-config state unreadable for tenant={}, stored report preferences kept: {}",
                    tenantSchema, e.toString());
            return new EffectivePreferences(stored.toView(), false);
        }
        if (state == null) {
            log.warn("report-config state unknown for tenant={}, stored report preferences kept", tenantSchema);
            return new EffectivePreferences(stored.toView(), false);
        }
        // Story 17.2: the leaderboard flag belongs to advanced-reports, not report-config — always the stored one.
        return new EffectivePreferences(state == ModuleState.DISABLED
                        ? TenantPreferencesView.fixedDefaults()
                                .withLeaderboardVisibleToEmployees(stored.leaderboardVisibleToEmployees())
                        : stored.toView(),
                state == ModuleState.ENABLED);
    }
}
