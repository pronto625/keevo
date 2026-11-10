package com.keevo.reporting.leaderboard.application.service;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Leaderboard audience — rule shared by REST and the AI tool (Story 17.2, extracted by 21.8)")
class LeaderboardAudienceTest {

    static final String TENANT = "kv_abc123";

    TenantPreferencesQueryPort preferences = mock(TenantPreferencesQueryPort.class);
    EntitlementService entitlements = mock(EntitlementService.class);
    LeaderboardAudience audience = new LeaderboardAudience(preferences, entitlements);

    private void shared(boolean shared, ModuleState state) {
        TenantPreferencesView view = mock(TenantPreferencesView.class);
        when(view.leaderboardVisibleToEmployees()).thenReturn(shared);
        when(preferences.find(TENANT)).thenReturn(Optional.of(view));
        when(entitlements.state(TENANT, LeaderboardAudience.ADVANCED_REPORTS)).thenReturn(state);
    }

    @Test
    @DisplayName("owner: the requested store (or all), names kept, the assigned store never read")
    void owner() {
        UUID store = UUID.randomUUID();
        assertThat(audience.resolve(TENANT, true, store, () -> {
            throw new AssertionError("not read");
        })).isEqualTo(new LeaderboardAudience.View(store, false));
        assertThat(audience.resolve(TENANT, true, null, () -> null).storeId()).isNull();
    }

    @Test
    @DisplayName("member: shared and ENABLED → own store (requested store ignored), anonymised")
    void sharedMember() {
        shared(true, ModuleState.ENABLED);
        UUID own = UUID.randomUUID();
        assertThat(audience.resolve(TENANT, false, UUID.randomUUID(), () -> own))
                .isEqualTo(new LeaderboardAudience.View(own, true));
    }

    @Test
    @DisplayName("member: not shared, or module in read-only grace → FORBIDDEN with the French message")
    void notShared() {
        shared(false, ModuleState.ENABLED);
        assertThatThrownBy(() -> audience.resolve(TENANT, false, null, UUID::randomUUID))
                .isInstanceOf(DomainException.class).hasMessageContaining("partagé");
        shared(true, ModuleState.READ_ONLY);
        assertThatThrownBy(() -> audience.resolve(TENANT, false, null, UUID::randomUUID))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.getUserMessage()).isEqualTo(LeaderboardAudience.NOT_SHARED_MESSAGE));
    }
}
