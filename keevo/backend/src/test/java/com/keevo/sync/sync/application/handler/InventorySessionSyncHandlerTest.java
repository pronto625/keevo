package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** v1s-12-10: CREATE_INVENTORY_SESSION is scoped to the employee's assigned store. */
@ExtendWith(MockitoExtension.class)
class InventorySessionSyncHandlerTest {

    @Mock private InventorySessionRepository repository;

    private InventorySessionSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_C = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new InventorySessionSyncHandler(repository);
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    private SyncOperation createOp(UUID sessionId, UUID storeId) {
        return new SyncOperation("op-1", "CREATE_INVENTORY_SESSION", sessionId.toString(),
                Map.of("sessionId", sessionId.toString(),
                        "storeId", storeId.toString(),
                        "scope", "FULL"),
                Instant.now());
    }

    @Test
    void supportedTypes_containsCreateInventorySession() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_INVENTORY_SESSION");
    }

    @Test
    void employee_otherStore_isRejectedForbidden_nothingReadNorSaved() {
        SyncTestAuth.employee(STORE_A);

        var result = handler.handle(createOp(UUID.randomUUID(), STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(repository);
    }

    @Test
    void employee_ownStore_isApplied() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(repository.findById(sessionId)).thenReturn(Optional.empty());

        var result = handler.handle(createOp(sessionId, STORE_A), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = ArgumentCaptor.forClass(InventorySession.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStoreId()).isEqualTo(STORE_A);
        assertThat(captor.getValue().getId()).isEqualTo(sessionId);
    }

    @Test
    void owner_anyStore_isApplied() {
        SyncTestAuth.owner();
        UUID sessionId = UUID.randomUUID();
        when(repository.findById(sessionId)).thenReturn(Optional.empty());

        var result = handler.handle(createOp(sessionId, STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(repository).save(any());
    }

    @Test
    void owner_replay_isIdempotentNoOp() {
        SyncTestAuth.owner();
        UUID sessionId = UUID.randomUUID();
        when(repository.findById(sessionId)).thenReturn(Optional.of(new InventorySession(
                sessionId, STORE_C, InventoryScope.FULL, null, InventorySessionStatus.IN_PROGRESS,
                ACTOR_ID, Instant.now(), null, null, null, Instant.now())));

        var result = handler.handle(createOp(sessionId, STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(repository, never()).save(any());
    }

    @Test
    void withoutAuthentication_isRejectedForbidden() {
        var result = handler.handle(createOp(UUID.randomUUID(), STORE_A), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(repository);
    }
}
