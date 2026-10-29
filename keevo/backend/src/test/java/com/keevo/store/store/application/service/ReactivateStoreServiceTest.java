package com.keevo.store.store.application.service;

import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.event.catalog.store.StoreReactivatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.in.ReactivateStoreCommand;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** J7 — réactivation d'une boutique désactivée, limite du forfait respectée. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReactivateStoreService")
class ReactivateStoreServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionLockPort transactionLock;

    ReactivateStoreService service;

    UUID storeId;
    UUID actorId;
    Store inactiveStore;
    Subscription subscription;

    @BeforeEach
    void setUp() {
        // 12.2d: real subscription API adapters over the mocked repository / guard
        service = new ReactivateStoreService(storeRepository, SubscriptionApiTestSupport.queryPort(subscriptionRepository),
                SubscriptionApiTestSupport.limitPort(planLimitGuard), eventPublisher, transactionLock);
        TenantContext.setCurrentTenant("kv_abc123");
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        inactiveStore = new Store(storeId, "Boutique Akwa", StoreType.STORE,
                null, null, false, Instant.now(), Instant.now());
        subscription = mock(Subscription.class);
        lenient().when(subscription.getPlanType()).thenReturn(PlanType.FREE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("réactive la boutique, sous verrou, et publie l'événement d'audit")
    void reactivates_underLock_andPublishesEvent() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(inactiveStore));
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(subscription));
        when(storeRepository.countActive()).thenReturn(1);
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = service.execute(new ReactivateStoreCommand(storeId, actorId));

        assertThat(result.isActive()).isTrue();
        assertThat(result.id()).isEqualTo(storeId);
        verify(transactionLock).lock("store-limit:kv_abc123");
        verify(planLimitGuard).checkStoreLimit(PlanType.FREE, 1);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(StoreReactivatedEvent.class);
        StoreReactivatedEvent event = (StoreReactivatedEvent) captor.getValue();
        assertThat(event.name()).isEqualTo("Boutique Akwa");
        assertThat(event.actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("refuse au-delà de la limite du forfait, sans rien enregistrer")
    void planLimitReached_refused() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(inactiveStore));
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(subscription));
        when(storeRepository.countActive()).thenReturn(3);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "stores limit reached"))
                .when(planLimitGuard).checkStoreLimit(PlanType.FREE, 3);

        assertThatThrownBy(() -> service.execute(new ReactivateStoreCommand(storeId, actorId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
        verify(storeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("boutique introuvable → STORE_NOT_FOUND")
    void unknownStore_notFound() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(new ReactivateStoreCommand(storeId, actorId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.STORE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("boutique déjà active → inchangée, aucun contrôle de forfait")
    void alreadyActive_noop() {
        Store active = new Store(storeId, "Boutique Akwa", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(active));

        Store result = service.execute(new ReactivateStoreCommand(storeId, actorId));

        assertThat(result).isSameAs(active);
        verifyNoInteractions(planLimitGuard, eventPublisher);
        verify(storeRepository, never()).save(any());
    }
}
