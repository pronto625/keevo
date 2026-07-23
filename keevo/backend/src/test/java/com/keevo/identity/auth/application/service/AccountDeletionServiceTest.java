package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AccountDeletionServiceTest — Unit tests for {@link AccountDeletionService}.
 *
 * <p>Mocks {@link TenantRepository}, {@link UserRepository},
 * {@link WhatsAppPort}, and {@link ApplicationEventPublisher}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionService")
class AccountDeletionServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AccountDeletionService service;

    private UUID tenantId;
    private UUID actorId;
    private String schemaName;
    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        service = new AccountDeletionService(tenantRepository, userRepository,
                whatsAppPort, eventPublisher);
        tenantId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        schemaName = "kv_abc123";

        activeTenant = new Tenant(tenantId, "KV-ABC123", schemaName, "Test Shop",
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now().minus(30, ChronoUnit.DAYS));
    }

    // ── requestDeletion ──────────────────────────────────────────────────────

    @Test
    @DisplayName("requestDeletion sets tenant to DELETION_PENDING with 30-day grace")
    void requestDeletion_setsPendingAndPublishesEvent() {
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(activeTenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.requestDeletion(actorId, schemaName);

        ArgumentCaptor<Tenant> savedCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(TenantStatus.DELETION_PENDING);
        assertThat(savedCaptor.getValue().getDeletionScheduledAt()).isNotNull();
        assertThat(savedCaptor.getValue().getDeletionScheduledAt())
                .isAfter(Instant.now().plus(29, ChronoUnit.DAYS));

        verify(eventPublisher).publishEvent(any(AccountDeletionRequestedEvent.class));
    }

    @Test
    @DisplayName("requestDeletion throws 409 when already DELETION_PENDING")
    void requestDeletion_throwsWhenAlreadyPending() {
        Tenant pending = activeTenant.withStatus(TenantStatus.DELETION_PENDING,
                Instant.now().plus(30, ChronoUnit.DAYS));
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.requestDeletion(actorId, schemaName))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).getDomainCode())
                .isEqualTo(ErrorCode.DELETION_ALREADY_REQUESTED.name());

        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("requestDeletion sends WhatsApp confirmation")
    void requestDeletion_sendsWhatsApp() {
        User owner = new User(actorId, "+243812345678", "hash",
                Role.OWNER, true, Instant.now().minus(10, ChronoUnit.DAYS));
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(activeTenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(owner));

        service.requestDeletion(actorId, schemaName);

        verify(whatsAppPort).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    @DisplayName("requestDeletion WhatsApp failure is non-blocking")
    void requestDeletion_whatsAppFailure_doesNotAbort() throws Exception {
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(activeTenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(actorId)).thenReturn(
                Optional.of(new User(actorId, "+243812345678", "hash",
                        Role.OWNER, true, Instant.now())));
        doThrow(new RuntimeException("WhatsApp down")).when(whatsAppPort).sendReport(anyString(), anyString());

        // Should NOT throw
        assertThatNoException().isThrownBy(() -> service.requestDeletion(actorId, schemaName));

        verify(eventPublisher).publishEvent(any(AccountDeletionRequestedEvent.class));
    }

    // ── cancelDeletion ───────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelDeletion restores tenant to ACTIVE and clears deletionScheduledAt")
    void cancelDeletion_restoresActive() {
        Tenant pending = activeTenant.withStatus(TenantStatus.DELETION_PENDING,
                Instant.now().plus(30, ChronoUnit.DAYS));
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(pending));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelDeletion(actorId, schemaName);

        ArgumentCaptor<Tenant> savedCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(savedCaptor.getValue().getDeletionScheduledAt()).isNull();

        verify(eventPublisher).publishEvent(any(AccountDeletionCancelledEvent.class));
        verify(whatsAppPort, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("cancelDeletion throws 409 when not DELETION_PENDING")
    void cancelDeletion_throwsWhenNotPending() {
        when(tenantRepository.findBySchemaName(schemaName)).thenReturn(Optional.of(activeTenant));

        assertThatThrownBy(() -> service.cancelDeletion(actorId, schemaName))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).getDomainCode())
                .isEqualTo(ErrorCode.DELETION_NOT_PENDING.name());

        verify(tenantRepository, never()).save(any());
    }
}
