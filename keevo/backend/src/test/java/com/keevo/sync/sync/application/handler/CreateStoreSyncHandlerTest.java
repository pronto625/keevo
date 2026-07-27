package com.keevo.sync.sync.application.handler;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateStoreSyncHandlerTest {

    @Mock private StoreRepository storeRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanLimitGuard planLimitGuard;

    private CreateStoreSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateStoreSyncHandler(storeRepository, subscriptionRepository, planLimitGuard);
        authenticateAsOwner();
    }

    private Subscription samplePlan(PlanType type) {
        Subscription sub = mock(Subscription.class);
        lenient().when(sub.getPlanType()).thenReturn(type);
        return sub;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    private Store sampleStore(UUID id) {
        return new Store(id, "Boutique Test", StoreType.STORE, null, null, true,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createStore_savesWithClientProvidedUUID() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(storeRepository.save(any())).thenReturn(sampleStore(storeId));
        Subscription premiumPlan = samplePlan(PlanType.PREMIUM_TRIAL);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumPlan));
        when(storeRepository.countActive()).thenReturn(0);

        var op = new SyncOperation("op-1", "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Boutique Test", "type", "STORE"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(storeId.toString());
        verify(storeRepository).save(argThat(s -> storeId.equals(s.id())));
    }

    @Test
    void handle_createStore_planLimitExceeded_returnsRejected() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        Subscription freePlan = samplePlan(PlanType.FREE);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freePlan));
        when(storeRepository.countActive()).thenReturn(1);
        doThrow(new com.keevo.shared.domain.exception.DomainException(
                ErrorCode.PLAN_LIMIT_EXCEEDED, "stores limit reached"))
                .when(planLimitGuard).checkStoreLimit(PlanType.FREE, 1);

        var op = new SyncOperation("op-8", "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Boutique Test", "type", "STORE"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        verify(storeRepository, never()).save(any());
    }

    @Test
    void handle_createStore_warehouseAlreadyExists_returnsRejected() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        Subscription premiumPlan = samplePlan(PlanType.PREMIUM_TRIAL);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumPlan));
        when(storeRepository.countActive()).thenReturn(1);
        when(storeRepository.existsWarehouse()).thenReturn(true);

        var op = new SyncOperation("op-9", "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Entrepot", "type", "WAREHOUSE"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("WAREHOUSE_ALREADY_EXISTS");
        verify(storeRepository, never()).save(any());
    }

    @Test
    void handle_createStore_idempotent_skipsWhenStoreAlreadyExists() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(sampleStore(storeId)));

        var op = new SyncOperation("op-2", "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Boutique Test", "type", "STORE"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(storeRepository, never()).save(any());
    }

    @Test
    void handle_createStore_missingId_returnsRejected() {
        var op = new SyncOperation("op-3", "CREATE_STORE", null,
                Map.of("name", "Boutique"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void handle_createStore_missingName_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_STORE", null,
                Map.of("id", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void handle_createStore_blankName_returnsRejected() {
        var op = new SyncOperation("op-5", "CREATE_STORE", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "   "),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void handle_createStore_defaultsTypeToStore_whenMissing() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());
        when(storeRepository.save(any())).thenReturn(sampleStore(storeId));
        Subscription premiumPlan = samplePlan(PlanType.PREMIUM_TRIAL);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumPlan));
        when(storeRepository.countActive()).thenReturn(0);

        var op = new SyncOperation("op-6", "CREATE_STORE", storeId.toString(),
                Map.of("id", storeId.toString(), "name", "Default Type"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(storeRepository).save(argThat(s -> s.type() == StoreType.STORE));
    }

    @Test
    void supportedTypes_containsCreateStore() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_STORE");
    }

    @Test
    void handle_createStore_employee_rejectedForbidden() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));

        var op = new SyncOperation("op-7", "CREATE_STORE", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Boutique"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(storeRepository);
    }
}
