package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.ReportChannel;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
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

/** Story 12.2d — preferences adapter of {@code identity :: api}. */
@ExtendWith(MockitoExtension.class)
class TenantPreferencesQueryAdapterTest {

    @Mock private TenantPreferencesRepository repository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void find_mapsEveryPreference() {
        TenantContext.setCurrentTenant("kv_abc123");
        var prefs = new TenantPreferences(UUID.randomUUID(), SectorType.values()[0], "19:30:00", false,
                Instant.now(), false, ReportChannel.IN_APP_ONLY, true, 5, "18:00:00", ReportChannel.WHATSAPP,
                false, ReportChannel.IN_APP_ONLY, StockAlertChannel.BOTH, false);
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(prefs));

        TenantPreferencesView v = new TenantPreferencesQueryAdapter(repository).find("kv_abc123").orElseThrow();

        assertThat(v).isEqualTo(new TenantPreferencesView("19:30:00", false, false, ReportChannel.IN_APP_ONLY,
                true, 5, "18:00:00", ReportChannel.WHATSAPP, false, ReportChannel.IN_APP_ONLY,
                StockAlertChannel.BOTH, false));
    }

    @Test
    void find_otherTenantThanContext_failsFast() {
        TenantContext.setCurrentTenant("kv_abc123");
        assertThatThrownBy(() -> new TenantPreferencesQueryAdapter(repository).find("kv_other1"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
}
