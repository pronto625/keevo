package com.keevo.sync.sync.application.handler;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
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
class StoreSyncHandlerTest {

    @Mock private UpdateStoreUseCase updateStoreUseCase;
    @Mock private DeactivateStoreUseCase deactivateStoreUseCase;
    @Mock private StoreRepository storeRepository;

    private StoreSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        // 12.2d: the real store API adapter over the mocked use cases / repository
        handler = new StoreSyncHandler(
                StoreApiTestSupport.commandPort(storeRepository, updateStoreUseCase, deactivateStoreUseCase, null, null, null),
                StoreApiTestSupport.queryPort(storeRepository));
        authenticateAsOwner();
        TenantContext.setCurrentTenant(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    private Store sampleStore(UUID id) {
        return new Store(id, "Boutique", StoreType.STORE, null, null, true,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_updateStore_delegatesToUseCase() {
        var storeId = UUID.randomUUID();
        when(updateStoreUseCase.execute(any())).thenReturn(sampleStore(storeId));

        var op = new SyncOperation("op-1", "UPDATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString(), "name", "Updated Name"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(storeId.toString());
        verify(updateStoreUseCase).execute(any(UpdateStoreCommand.class));
    }

    @Test
    void handle_deactivateStore_delegatesToUseCase() {
        var storeId = UUID.randomUUID();

        var op = new SyncOperation("op-2", "DEACTIVATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(deactivateStoreUseCase).execute(any(DeactivateStoreCommand.class));
    }

    @Test
    void handle_missingStoreId_returnsRejected() {
        var op = new SyncOperation("op-3", "UPDATE_STORE", null,
                Map.of("name", "No ID"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }

    @Test
    void handle_employee_rejectedForbidden() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));

        var op = new SyncOperation("op-4", "UPDATE_STORE", null,
                Map.of("storeId", UUID.randomUUID().toString(), "name", "X"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(updateStoreUseCase);
        verifyNoInteractions(deactivateStoreUseCase);
    }

    @Test
    void supportedTypes_containsUpdateAndDeactivate() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(
                "UPDATE_STORE", "DEACTIVATE_STORE");
    }

    // ── v1s-13-8: state-based idempotency (no 2nd domain event on replay) ──

    @Test
    void handle_deactivateStore_alreadyInactive_isAppliedNoOp() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(new Store(
                storeId, "Boutique", StoreType.STORE, null, null, false, Instant.now(), Instant.now())));

        var op = new SyncOperation("op-r1", "DEACTIVATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(storeId.toString());
        verifyNoInteractions(deactivateStoreUseCase);
    }

    @Test
    void handle_updateStore_sameValues_isAppliedNoOp() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(new Store(
                storeId, "Boutique", StoreType.STORE, "Rue 1", "+237690000001", true,
                Instant.now(), Instant.now())));

        var op = new SyncOperation("op-r2", "UPDATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString(), "name", "  Boutique ",
                        "address", "Rue 1", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(storeId.toString());
        verifyNoInteractions(updateStoreUseCase);
    }

    @Test
    void handle_updateStore_changedValue_callsUseCase() {
        var storeId = UUID.randomUUID();
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(new Store(
                storeId, "Boutique", StoreType.STORE, "Rue 1", null, true,
                Instant.now(), Instant.now())));
        when(updateStoreUseCase.execute(any())).thenReturn(sampleStore(storeId));

        var op = new SyncOperation("op-r3", "UPDATE_STORE", storeId.toString(),
                Map.of("storeId", storeId.toString(), "name", "Boutique", "address", "Rue 2"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(updateStoreUseCase).execute(any(UpdateStoreCommand.class));
    }
}
