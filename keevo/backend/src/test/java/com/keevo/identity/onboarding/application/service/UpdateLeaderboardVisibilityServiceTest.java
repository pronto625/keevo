package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.in.UpdateLeaderboardVisibilityCommand;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.onboarding.LeaderboardVisibilityChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateLeaderboardVisibilityService — Story 17.2")
class UpdateLeaderboardVisibilityServiceTest {

    private static final String TENANT = "kv_lb_17_2";
    private static final UUID OWNER = UUID.randomUUID();

    @Mock private TenantPreferencesRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private UpdateLeaderboardVisibilityService service;
    private final TenantPreferences stored = TenantPreferences.withDefaults(
            UUID.randomUUID(), SectorType.OTHER, "21:00:00", false, Instant.parse("2026-05-01T10:00:00Z"));

    @BeforeEach
    void setUp() {
        service = new UpdateLeaderboardVisibilityService(repository, eventPublisher);
        TenantContext.setCurrentTenant(TENANT);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("default off → on: only the flag changes, audited « partagé »")
    void turnsOn() {
        assertThat(stored.leaderboardVisibleToEmployees()).isFalse();
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(repository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        TenantPreferences saved = service.update(new UpdateLeaderboardVisibilityCommand(true));

        assertThat(saved).isEqualTo(stored.withLeaderboardVisibleToEmployees(true));
        assertThat(saved.eodReportTime()).isEqualTo("21:00:00");
        assertThat(saved.stockAlertEnabled()).isFalse();
        var event = ArgumentCaptor.forClass(LeaderboardVisibilityChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().visibleToEmployees()).isTrue();
        assertThat(event.getValue().preferencesId()).isEqualTo(stored.id());
        assertThat(event.getValue().actorId()).isEqualTo(OWNER);
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("on → off: audited « masqué »")
    void turnsOff() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored.withLeaderboardVisibleToEmployees(true)));
        when(repository.update(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(new UpdateLeaderboardVisibilityCommand(false)).leaderboardVisibleToEmployees())
                .isFalse();
        var event = ArgumentCaptor.forClass(LeaderboardVisibilityChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().visibleToEmployees()).isFalse();
    }

    @Test
    @DisplayName("same value → no write, no audit")
    void sameValueIsNoOp() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));

        assertThat(service.update(new UpdateLeaderboardVisibilityCommand(false))).isEqualTo(stored);
        verify(repository, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("no preferences row → PREFERENCES_NOT_FOUND")
    void noRow() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(new UpdateLeaderboardVisibilityCommand(true)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.PREFERENCES_NOT_FOUND));
        verifyNoInteractions(eventPublisher);
    }
}
