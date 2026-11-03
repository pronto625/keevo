package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.GetEffectiveTenantPreferencesUseCase.EffectivePreferences;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Story 17.8 — the effective-preferences rule (values and {@code reportConfigEditable} from one state read). */
@ExtendWith(MockitoExtension.class)
class EffectiveTenantPreferencesServiceTest {

    private static final String SCHEMA = "kv_abc123";

    @Mock private EntitlementService entitlements;

    private final TenantPreferences stored = new TenantPreferences(UUID.randomUUID(), SectorType.values()[0],
            "23:00", false, Instant.now(), false, ReportChannel.IN_APP_ONLY, true, 5, "18:00",
            ReportChannel.WHATSAPP, false, ReportChannel.IN_APP_ONLY, StockAlertChannel.BOTH, false);

    private final TenantPreferencesView storedView = new TenantPreferencesView("23:00", false, false,
            ReportChannel.IN_APP_ONLY, true, 5, "18:00", ReportChannel.WHATSAPP, false, ReportChannel.IN_APP_ONLY,
            StockAlertChannel.BOTH, false);

    @Test
    void flagFollowsTheSameStateRead() {
        var service = new EffectiveTenantPreferencesService(entitlements);
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.ENABLED, ModuleState.READ_ONLY,
                ModuleState.DISABLED).thenThrow(new IllegalStateException("db down"));

        assertThat(service.effective(SCHEMA, stored)).isEqualTo(new EffectivePreferences(storedView, true));
        assertThat(service.effective(SCHEMA, stored)).isEqualTo(new EffectivePreferences(storedView, false));
        assertThat(service.effective(SCHEMA, stored))
                .isEqualTo(new EffectivePreferences(TenantPreferencesView.fixedDefaults(), false));
        assertThat(service.effective(SCHEMA, stored)).isEqualTo(new EffectivePreferences(storedView, false));
    }

    @Test
    void unknownState_storedValuesNotEditable() {
        var service = new EffectiveTenantPreferencesService(entitlements);
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(null);

        assertThat(service.effective(SCHEMA, stored)).isEqualTo(new EffectivePreferences(storedView, false));
    }

    @Test
    void disabled_keepsStoredLeaderboardFlag() { // Story 17.2
        var service = new EffectiveTenantPreferencesService(entitlements);
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.DISABLED);

        assertThat(service.effective(SCHEMA, stored.withLeaderboardVisibleToEmployees(true)).view())
                .isEqualTo(TenantPreferencesView.fixedDefaults().withLeaderboardVisibleToEmployees(true));
    }
}
