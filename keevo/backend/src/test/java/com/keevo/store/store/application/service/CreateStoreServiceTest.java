package com.keevo.store.store.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.in.CreateStoreCommand;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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

/**
 * CreateStoreServiceTest — TDD unit tests (Story 3.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateStoreService")
class CreateStoreServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PlanLimitGuard planLimitGuard;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks CreateStoreService createStoreService;

    UUID actorId;
    Subscription freeSubscription;
    Subscription premiumSubscription;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        freeSubscription = mock(Subscription.class);
        lenient().when(freeSubscription.getPlanType()).thenReturn(PlanType.FREE);
        premiumSubscription = mock(Subscription.class);
        lenient().when(premiumSubscription.getPlanType()).thenReturn(PlanType.PREMIUM_TRIAL);
    }

    @Test
    @DisplayName("execute() should create store when plan limit not reached")
    void execute_shouldCreateStore_whenPlanLimitNotReached() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(storeRepository.countActive()).thenReturn(0);
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateStoreCommand cmd = new CreateStoreCommand("Boutique Test", StoreType.STORE, null, null, actorId);
        Store result = createStoreService.execute(cmd);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Boutique Test");
        assertThat(result.type()).isEqualTo(StoreType.STORE);
        verify(storeRepository).save(any());
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("execute() should throw PLAN_LIMIT_EXCEEDED when free stores full")
    void execute_shouldThrow_PLAN_LIMIT_EXCEEDED_whenFreeStoresFull() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSubscription));
        when(storeRepository.countActive()).thenReturn(3);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "stores limit reached"))
                .when(planLimitGuard).checkStoreLimit(PlanType.FREE, 3);

        CreateStoreCommand cmd = new CreateStoreCommand("4th Store", StoreType.STORE, null, null, actorId);
        assertThatThrownBy(() -> createStoreService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED.name());
    }

    @Test
    @DisplayName("execute() should throw WAREHOUSE_ALREADY_EXISTS when duplicate warehouse")
    void execute_shouldThrow_WAREHOUSE_ALREADY_EXISTS_whenDuplicateWarehouse() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(storeRepository.countActive()).thenReturn(1);
        when(storeRepository.existsWarehouse()).thenReturn(true);

        CreateStoreCommand cmd = new CreateStoreCommand("Second WH", StoreType.WAREHOUSE, null, null, actorId);
        assertThatThrownBy(() -> createStoreService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.WAREHOUSE_ALREADY_EXISTS.name());
    }

    @Test
    @DisplayName("execute() should publish StoreCreatedEvent on success")
    void execute_shouldPublishStoreCreatedEvent() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSubscription));
        when(storeRepository.countActive()).thenReturn(0);
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        createStoreService.execute(new CreateStoreCommand("Test", StoreType.STORE, null, null, actorId));

        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("execute() should use PlanLimitGuard with current store count")
    void execute_shouldUsePlanLimitGuard_withCurrentStoreCount() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSubscription));
        when(storeRepository.countActive()).thenReturn(2);
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        createStoreService.execute(new CreateStoreCommand("New Store", StoreType.STORE, null, null, actorId));

        verify(planLimitGuard).checkStoreLimit(PlanType.FREE, 2);
    }
}
