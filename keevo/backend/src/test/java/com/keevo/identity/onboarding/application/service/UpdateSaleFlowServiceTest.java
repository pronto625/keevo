package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.onboarding.SaleFlowChangedEvent;
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
@DisplayName("UpdateSaleFlowService — Story 19.12")
class UpdateSaleFlowServiceTest {

    private static final String TENANT = "kv_sf_19_12";
    private static final UUID OWNER = UUID.randomUUID();

    @Mock private TenantPreferencesRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private UpdateSaleFlowService service;
    private final TenantPreferences stored = TenantPreferences.withDefaults(
            UUID.randomUUID(), SectorType.OTHER, "21:00:00", false, Instant.parse("2026-05-01T10:00:00Z"));

    @BeforeEach
    void setUp() {
        service = new UpdateSaleFlowService(repository, eventPublisher);
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
    @DisplayName("a new tenant defaults to POS")
    void defaultIsPos() {
        assertThat(stored.defaultSaleFlow()).isEqualTo(SaleFlow.POS);
        assertThat(stored.withLeaderboardVisibleToEmployees(true).defaultSaleFlow()).isEqualTo(SaleFlow.POS);
        assertThat(stored.withDefaultSaleFlow(SaleFlow.DOCUMENT).withLeaderboardVisibleToEmployees(true)
                .defaultSaleFlow()).isEqualTo(SaleFlow.DOCUMENT);
    }

    @Test
    @DisplayName("POS → DOCUMENT: only the sale flow is written, audited « Caisse » → « Documents »")
    void change() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(repository.updateDefaultSaleFlow(stored.id(), SaleFlow.DOCUMENT)).thenReturn(true);

        assertThat(service.update(SaleFlow.DOCUMENT).defaultSaleFlow()).isEqualTo(SaleFlow.DOCUMENT);

        verify(repository, never()).update(any());
        ArgumentCaptor<SaleFlowChangedEvent> event = ArgumentCaptor.forClass(SaleFlowChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().beforeLabel()).isEqualTo("Caisse");
        assertThat(event.getValue().afterLabel()).isEqualTo("Documents");
        assertThat(event.getValue().actorId()).isEqualTo(OWNER);
        assertThat(event.getValue().tenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("same value: no write, no audit")
    void sameValue() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));

        assertThat(service.update(SaleFlow.POS)).isSameAs(stored);

        verify(repository, never()).updateDefaultSaleFlow(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("no preferences row → PREFERENCES_NOT_FOUND")
    void noRow() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(SaleFlow.DOCUMENT))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("PREFERENCES_NOT_FOUND");
    }

    @Test
    @DisplayName("a concurrent change already wrote the value (conditional UPDATE changed nothing): no audit")
    void concurrentSameValue() {
        when(repository.findByCurrentTenant()).thenReturn(Optional.of(stored));
        when(repository.updateDefaultSaleFlow(stored.id(), SaleFlow.DOCUMENT)).thenReturn(false);

        assertThat(service.update(SaleFlow.DOCUMENT).defaultSaleFlow()).isEqualTo(SaleFlow.DOCUMENT);
        verifyNoInteractions(eventPublisher);
    }
}
