package com.keevo.sync.sync.application.handler;

import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreSyncHandlerTest {

    @Mock private UpdateStoreUseCase updateStoreUseCase;
    @Mock private DeactivateStoreUseCase deactivateStoreUseCase;

    private StoreSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new StoreSyncHandler(updateStoreUseCase, deactivateStoreUseCase);
        authenticateAsOwner();
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
}
