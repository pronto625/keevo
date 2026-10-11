package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1s-12-10: CREATE_INVENTORY_SESSION is scoped to the employee's assigned store.
 * v1s-13-8: the handler delegates to {@link CreateInventorySessionUseCase} with the client id.
 */
@ExtendWith(MockitoExtension.class)
class InventorySessionSyncHandlerTest {

    @Mock private CreateInventorySessionUseCase useCase;

    private InventorySessionSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_C = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new InventorySessionSyncHandler(useCase);
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

    private void useCaseEchoes() {
        when(useCase.execute(any())).thenAnswer(inv -> {
            CreateInventorySessionCommand c = inv.getArgument(0);
            return InventorySession.create(c.sessionId(), c.storeId(), c.scope(), c.categoryIds(), c.actorId());
        });
    }

    @Test
    void supportedTypes_containsCreateInventorySession() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_INVENTORY_SESSION");
    }

    @Test
    void employee_otherStore_isRejectedForbidden_useCaseNeverCalled() {
        SyncTestAuth.employee(STORE_A);

        var result = handler.handle(createOp(UUID.randomUUID(), STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(useCase);
    }

    @Test
    void employee_ownStore_isApplied_viaUseCaseWithClientId() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        useCaseEchoes();

        var result = handler.handle(createOp(sessionId, STORE_A), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(sessionId.toString());
        var captor = ArgumentCaptor.forClass(CreateInventorySessionCommand.class);
        verify(useCase).execute(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(captor.getValue().storeId()).isEqualTo(STORE_A);
        assertThat(captor.getValue().scope()).isEqualTo(InventoryScope.FULL);
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void owner_anyStore_isApplied() {
        SyncTestAuth.owner();
        UUID sessionId = UUID.randomUUID();
        useCaseEchoes();

        var result = handler.handle(createOp(sessionId, STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(useCase).execute(any());
    }

    @Test
    void owner_replay_isApplied_useCaseReturnsExisting() {
        SyncTestAuth.owner();
        UUID sessionId = UUID.randomUUID();
        useCaseEchoes();

        var first = handler.handle(createOp(sessionId, STORE_C), ACTOR_ID, TENANT_ID);
        var second = handler.handle(createOp(sessionId, STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(first.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(second.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(second.serverEntityId()).isEqualTo(sessionId.toString());
    }

    @Test
    void activeSessionConflict_isRejectedAlreadyActive() {
        SyncTestAuth.owner();
        when(useCase.execute(any())).thenThrow(new DomainException(
                ErrorCode.INVENTORY_SESSION_ALREADY_ACTIVE, "Active session exists for store"));

        var result = handler.handle(createOp(UUID.randomUUID(), STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INVENTORY_SESSION_ALREADY_ACTIVE");
    }

    @Test
    void unknownStore_isRejectedStoreNotFound() {
        SyncTestAuth.owner();
        when(useCase.execute(any())).thenThrow(new DomainException(
                ErrorCode.INVENTORY_STORE_NOT_FOUND, "Store not found"));

        var result = handler.handle(createOp(UUID.randomUUID(), STORE_C), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INVENTORY_STORE_NOT_FOUND");
    }

    @Test
    void withoutAuthentication_isRejectedForbidden() {
        var result = handler.handle(createOp(UUID.randomUUID(), STORE_A), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(useCase);
    }
}
