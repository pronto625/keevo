package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryCountSyncHandlerTest {

    @Mock private SaveInventoryCountUseCase saveInventoryCountUseCase;
    @Mock private InventorySessionRepository sessionRepository;

    private InventoryCountSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new InventoryCountSyncHandler(saveInventoryCountUseCase, sessionRepository);
        SyncTestAuth.owner();
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    @Test
    void supportedTypes_containsSAVE_INVENTORY_COUNT() {
        assertThat(handler.supportedTypes()).containsExactly("SAVE_INVENTORY_COUNT");
    }

    @Test
    void handle_validPayload_shouldDelegateAndReturnApplied() {
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var saved = InventoryCount.create(sessionId, productId, null,
                "Savon", null, 50, 47, ACTOR_ID);
        when(saveInventoryCountUseCase.execute(any())).thenReturn(saved);

        var op = new SyncOperation("op-1", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", sessionId.toString(),
                        "productId", productId.toString(),
                        "productName", "Savon",
                        "theoretical", 50,
                        "physical", 47),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.operationId()).isEqualTo("op-1");
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(saveInventoryCountUseCase).execute(any());
    }

    @Test
    void handle_missingRequiredField_shouldReturnRejected() {
        var op = new SyncOperation("op-2", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(saveInventoryCountUseCase, never()).execute(any());
    }

    @Test
    void handle_domainException_shouldReturnRejected() {
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(saveInventoryCountUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS, "Not in progress"));

        var op = new SyncOperation("op-3", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", sessionId.toString(),
                        "productId", productId.toString(),
                        "productName", "P",
                        "theoretical", 10,
                        "physical", 8),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INVENTORY_SESSION_NOT_IN_PROGRESS");
    }

    // The device's count id is kept so the pull updates its own row.
    @Test
    void handle_passesEntityIdAsCountId() {
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID countId = UUID.randomUUID();
        when(saveInventoryCountUseCase.execute(any())).thenAnswer(inv -> {
            com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand cmd = inv.getArgument(0);
            return InventoryCount.create(cmd.countId(), sessionId, productId, null, "Savon", null, 50, 47, ACTOR_ID);
        });

        var op = new SyncOperation("op-4", "SAVE_INVENTORY_COUNT", countId.toString(),
                Map.of("sessionId", sessionId.toString(),
                        "productId", productId.toString(),
                        "productName", "Savon",
                        "theoretical", 50,
                        "physical", 47),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand.class);
        verify(saveInventoryCountUseCase).execute(captor.capture());
        assertThat(captor.getValue().countId()).isEqualTo(countId);
        assertThat(result.serverEntityId()).isEqualTo(countId.toString());
    }

    // ── v1s-12-10: counts scoped to the employee's store ────────────────────

    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_C = UUID.randomUUID();

    private InventorySession sessionOf(UUID sessionId, UUID storeId) {
        return new InventorySession(sessionId, storeId, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, ACTOR_ID, Instant.now(),
                null, null, null, Instant.now());
    }

    private SyncOperation countOp(UUID sessionId) {
        return new SyncOperation("op-s", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", sessionId.toString(),
                        "productId", UUID.randomUUID().toString(),
                        "productName", "Savon",
                        "theoretical", 50,
                        "physical", 47),
                Instant.now());
    }

    @Test
    void handle_employeeCountInForeignStoreSession_isRejectedForbidden_andUseCaseNeverCalled() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sessionOf(sessionId, STORE_C)));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(saveInventoryCountUseCase, never()).execute(any());
    }

    @Test
    void handle_employeeCountInOwnStoreSession_isApplied() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sessionOf(sessionId, STORE_A)));
        when(saveInventoryCountUseCase.execute(any())).thenReturn(
                InventoryCount.create(sessionId, UUID.randomUUID(), null, "Savon", null, 50, 47, ACTOR_ID));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void handle_employeeCountUnknownSession_isDelegatedToUseCase() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(saveInventoryCountUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "not found"));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INVENTORY_SESSION_NOT_FOUND");
        verify(saveInventoryCountUseCase).execute(any());
    }

    @Test
    void handle_ownerCount_isUnscoped_noSessionLookup() {
        UUID sessionId = UUID.randomUUID();
        when(saveInventoryCountUseCase.execute(any())).thenReturn(
                InventoryCount.create(sessionId, UUID.randomUUID(), null, "Savon", null, 50, 47, ACTOR_ID));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void handle_countWithoutAuthentication_isRejectedForbidden() {
        SyncTestAuth.clear();

        var result = handler.handle(countOp(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(saveInventoryCountUseCase, never()).execute(any());
    }
}
