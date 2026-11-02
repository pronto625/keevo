package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.2d — preferences adapter of {@code identity :: api}; Story 17.8 — effective preferences. */
@ExtendWith(MockitoExtension.class)
class TenantPreferencesQueryAdapterTest {

    private static final String SCHEMA = "kv_abc123";

    @Mock private TenantPreferencesRepository repository;
    @Mock private EntitlementService entitlements;

    private TenantPreferencesQueryAdapter adapter;

    /** What a tenant configured during its trial: EOD off at 23:00 in-app only, weekly on Friday 18:00, etc. */
    private final TenantPreferences stored = new TenantPreferences(UUID.randomUUID(), SectorType.values()[0],
            "23:00:00", false, Instant.now(), false, ReportChannel.IN_APP_ONLY, true, 5, "18:00:00",
            ReportChannel.WHATSAPP, false, ReportChannel.IN_APP_ONLY, StockAlertChannel.BOTH, false);

    private final TenantPreferencesView storedView = new TenantPreferencesView("23:00:00", false, false,
            ReportChannel.IN_APP_ONLY, true, 5, "18:00:00", ReportChannel.WHATSAPP, false, ReportChannel.IN_APP_ONLY,
            StockAlertChannel.BOTH, false);

    @BeforeEach
    void setUp() {
        adapter = new TenantPreferencesQueryAdapter(repository, entitlements);
        TenantContext.setCurrentTenant(SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void find_reportConfigEnabled_mapsEveryStoredPreference() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.ENABLED);

        assertThat(adapter.find(SCHEMA)).contains(storedView);
    }

    @Test
    void find_reportConfigDisabled_returnsFixedDefaults() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.DISABLED);

        assertThat(adapter.find(SCHEMA)).contains(new TenantPreferencesView("20:00", true, true,
                ReportChannel.WHATSAPP, true, 0, "20:00", ReportChannel.WHATSAPP, true, ReportChannel.WHATSAPP,
                StockAlertChannel.PUSH, true));
        assertThat(adapter.find(SCHEMA)).contains(TenantPreferencesView.fixedDefaults());
    }

    @Test
    void find_reportConfigReadOnlyGrace_keepsStoredValues() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.READ_ONLY);

        assertThat(adapter.find(SCHEMA)).contains(storedView);
    }

    @Test
    void find_moduleStateUnreadable_failsOpenOnStoredValues() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(entitlements.state(SCHEMA, "report-config")).thenThrow(new IllegalStateException("db down"));

        assertThat(adapter.find(SCHEMA)).contains(storedView);
    }

    @Test
    void effective_flagFollowsTheSameStateRead() {
        when(entitlements.state(SCHEMA, "report-config")).thenReturn(ModuleState.ENABLED, ModuleState.READ_ONLY,
                ModuleState.DISABLED).thenThrow(new IllegalStateException("db down"));

        assertThat(adapter.effective(SCHEMA, stored))
                .isEqualTo(new TenantPreferencesQueryAdapter.Effective(storedView, true));
        assertThat(adapter.effective(SCHEMA, stored))
                .isEqualTo(new TenantPreferencesQueryAdapter.Effective(storedView, false));
        assertThat(adapter.effective(SCHEMA, stored))
                .isEqualTo(new TenantPreferencesQueryAdapter.Effective(TenantPreferencesView.fixedDefaults(), false));
        assertThat(adapter.effective(SCHEMA, stored))
                .isEqualTo(new TenantPreferencesQueryAdapter.Effective(storedView, false));
    }

    @Test
    void find_noPreferencesRow_emptyWithoutEntitlementCheck() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.empty());

        assertThat(adapter.find(SCHEMA)).isEmpty();
        verifyNoInteractions(entitlements);
    }

    @Test
    void find_otherTenantThanContext_failsFast() {
        assertThatThrownBy(() -> adapter.find("kv_other1")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository, entitlements);
    }
}
