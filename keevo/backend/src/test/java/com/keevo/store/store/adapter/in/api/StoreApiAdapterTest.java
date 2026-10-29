package com.keevo.store.store.adapter.in.api;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.CreateStoreWithIdInput;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.UpdateStoreInput;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.PlanView;
import com.keevo.subscription.api.SubscriptionQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2d — {@code store :: api} adapter: delegation, mapping, tenant guard, offline creation rules. */
@ExtendWith(MockitoExtension.class)
class StoreApiAdapterTest {

    private static final String TENANT = "kv_abc123";
    private static final PlanView FREE = new PlanView(PlanType.FREE, true, null);

    @Mock private StoreRepository storeRepository;
    @Mock private UpdateStoreUseCase updateStoreUseCase;
    @Mock private DeactivateStoreUseCase deactivateStoreUseCase;
    @Mock private SubscriptionQueryPort subscriptionQuery;
    @Mock private PlanLimitPort planLimit;
    @Mock private TransactionLockPort transactionLock;

    private StoreApiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StoreApiAdapter(storeRepository, updateStoreUseCase, deactivateStoreUseCase,
                subscriptionQuery, planLimit, transactionLock);
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Store store(UUID id, StoreType type, boolean active) {
        Instant created = Instant.parse("2026-10-01T08:00:00Z");
        return new Store(id, "Boutique Akwa", type, "Akwa", "+237690000000", active, created,
                created.plusSeconds(60));
    }

    @Test
    void findById_mapsEveryField() {
        UUID id = UUID.randomUUID();
        Store s = store(id, StoreType.WAREHOUSE, false);
        when(storeRepository.findById(id)).thenReturn(Optional.of(s));

        StoreView v = adapter.findById(TENANT, id).orElseThrow();

        assertThat(v).isEqualTo(new StoreView(id, "Boutique Akwa", StoreType.WAREHOUSE, "Akwa", "+237690000000",
                false, s.createdAt(), s.updatedAt()));
        assertThat(v.isWarehouse()).isTrue();
    }

    @Test
    void findAllActive_and_countActive_delegate() {
        UUID id = UUID.randomUUID();
        when(storeRepository.findAllActive()).thenReturn(List.of(store(id, StoreType.STORE, true)));
        when(storeRepository.countActive()).thenReturn(3);

        assertThat(adapter.findAllActive(TENANT)).extracting(StoreView::id).containsExactly(id);
        assertThat(adapter.countActive(TENANT)).isEqualTo(3);
    }

    @Test
    void tenantSchemaOtherThanTheCurrentContext_failsFast() {
        assertThatThrownBy(() -> adapter.findById("kv_other1", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.countActive(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.createWithId(" ",
                new CreateStoreWithIdInput(UUID.randomUUID(), "Boutique", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storeRepository, transactionLock);
    }

    @Test
    void createWithId_checksPlanBeforeSaving_defaultsToStore_trimsName() {
        UUID id = UUID.randomUUID();
        when(subscriptionQuery.findCurrentPlan(TENANT)).thenReturn(Optional.of(FREE));
        when(storeRepository.countActive()).thenReturn(0);

        boolean created = adapter.createWithId(TENANT,
                new CreateStoreWithIdInput(id, "  Boutique Bonapriso ", null, null, "+237"));

        assertThat(created).isTrue();
        InOrder order = inOrder(transactionLock, storeRepository, planLimit);
        order.verify(transactionLock).lock("store-limit:" + TENANT); // same key as CreateStoreService
        order.verify(storeRepository).findById(id);                  // idempotency read under the lock
        order.verify(planLimit).checkStoreLimit(FREE, 0);
        ArgumentCaptor<Store> saved = ArgumentCaptor.forClass(Store.class);
        order.verify(storeRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(id);
        assertThat(saved.getValue().name()).isEqualTo("Boutique Bonapriso");
        assertThat(saved.getValue().type()).isEqualTo(StoreType.STORE);
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().phone()).isEqualTo("+237");
    }

    @Test
    void createWithId_existingId_lockedThenNoOp() {
        UUID id = UUID.randomUUID();
        when(storeRepository.findById(id)).thenReturn(Optional.of(store(id, StoreType.STORE, true)));

        boolean created = adapter.createWithId(TENANT, new CreateStoreWithIdInput(id, "Boutique", null, null, null));

        assertThat(created).isFalse();
        InOrder order = inOrder(transactionLock, storeRepository);
        order.verify(transactionLock).lock("store-limit:" + TENANT);
        order.verify(storeRepository).findById(id);
        verify(storeRepository, never()).save(any());
        verifyNoInteractions(subscriptionQuery, planLimit);
    }

    @Test
    void createWithId_planLimitReached_nothingSaved() {
        when(subscriptionQuery.findCurrentPlan(TENANT)).thenReturn(Optional.of(FREE));
        when(storeRepository.countActive()).thenReturn(1);
        doThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "stores limit reached"))
                .when(planLimit).checkStoreLimit(FREE, 1);

        assertThatThrownBy(() -> adapter.createWithId(TENANT,
                new CreateStoreWithIdInput(UUID.randomUUID(), "Boutique", StoreType.STORE, null, null)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));
        verify(storeRepository, never()).save(any());
    }

    @Test
    void createWithId_noSubscription_subscriptionNotFound() {
        when(subscriptionQuery.findCurrentPlan(TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.createWithId(TENANT,
                new CreateStoreWithIdInput(UUID.randomUUID(), "Boutique", null, null, null)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        verify(storeRepository, never()).save(any());
    }

    @Test
    void createWithId_secondWarehouse_refused() {
        when(subscriptionQuery.findCurrentPlan(TENANT)).thenReturn(Optional.of(FREE));
        when(storeRepository.existsWarehouse()).thenReturn(true);

        assertThatThrownBy(() -> adapter.createWithId(TENANT,
                new CreateStoreWithIdInput(UUID.randomUUID(), "Dépôt", StoreType.WAREHOUSE, null, null)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WAREHOUSE_ALREADY_EXISTS));
        verify(storeRepository, never()).save(any());
    }

    @Test
    void validateName_appliesTheStoreRule() {
        assertThat(adapter.validateName("  Akwa ")).isEqualTo("Akwa");
        assertThatThrownBy(() -> adapter.validateName("A"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void update_and_deactivate_delegateToTheUseCases() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(updateStoreUseCase.execute(new UpdateStoreCommand(id, "Akwa", "Rue 1", "+237", actor)))
                .thenReturn(store(id, StoreType.STORE, true));

        StoreView v = adapter.update(TENANT, new UpdateStoreInput(id, "Akwa", "Rue 1", "+237", actor));
        adapter.deactivate(TENANT, id, actor);

        assertThat(v.id()).isEqualTo(id);
        verify(deactivateStoreUseCase).execute(new DeactivateStoreCommand(id, actor));
    }

    @Test
    void inputsWithoutStoreId_validationError() {
        assertThatThrownBy(() -> new UpdateStoreInput(null, "A", null, null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> new CreateStoreWithIdInput(null, "A", null, null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
