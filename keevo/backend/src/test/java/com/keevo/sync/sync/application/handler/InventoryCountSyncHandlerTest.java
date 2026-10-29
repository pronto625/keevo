package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.CountingCommandPort;
import com.keevo.inventory.api.CountingQueryPort;
import com.keevo.inventory.api.SaveCountInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private CountingCommandPort countingCommand;
    @Mock private CountingQueryPort countingQuery;

    private InventoryCountSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new InventoryCountSyncHandler(countingCommand, countingQuery);
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
        when(countingCommand.saveCount(any(), any())).thenReturn(UUID.randomUUID());

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
        verify(countingCommand).saveCount(eq(TENANT_ID), any());
    }

    @Test
    void handle_missingRequiredField_shouldReturnRejected() {
        var op = new SyncOperation("op-2", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(countingCommand, never()).saveCount(any(), any());
    }

    @Test
    void handle_domainException_shouldReturnRejected() {
        UUID sessionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(countingCommand.saveCount(any(), any()))
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
        when(countingCommand.saveCount(any(), any())).thenAnswer(inv -> {
            SaveCountInput in = inv.getArgument(1);
            return in.countId();
        });

        var op = new SyncOperation("op-4", "SAVE_INVENTORY_COUNT", countId.toString(),
                Map.of("sessionId", sessionId.toString(),
                        "productId", productId.toString(),
                        "productName", "Savon",
                        "theoretical", 50,
                        "physical", 47),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(SaveCountInput.class);
        verify(countingCommand).saveCount(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue().countId()).isEqualTo(countId);
        assertThat(result.serverEntityId()).isEqualTo(countId.toString());
    }

    // ── v1s-12-10: counts scoped to the employee's store ────────────────────

    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_C = UUID.randomUUID();

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
        when(countingQuery.findSessionStoreId(TENANT_ID, sessionId)).thenReturn(Optional.of(STORE_C));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(countingCommand, never()).saveCount(any(), any());
    }

    @Test
    void handle_employeeCountInOwnStoreSession_isApplied() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(countingQuery.findSessionStoreId(TENANT_ID, sessionId)).thenReturn(Optional.of(STORE_A));
        when(countingCommand.saveCount(any(), any())).thenReturn(UUID.randomUUID());

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void handle_employeeCountUnknownSession_isDelegatedToUseCase() {
        SyncTestAuth.employee(STORE_A);
        UUID sessionId = UUID.randomUUID();
        when(countingQuery.findSessionStoreId(TENANT_ID, sessionId)).thenReturn(Optional.empty());
        when(countingCommand.saveCount(any(), any()))
                .thenThrow(new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "not found"));

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INVENTORY_SESSION_NOT_FOUND");
        verify(countingCommand).saveCount(eq(TENANT_ID), any());
    }

    @Test
    void handle_ownerCount_isUnscoped_noSessionLookup() {
        UUID sessionId = UUID.randomUUID();
        when(countingCommand.saveCount(any(), any())).thenReturn(UUID.randomUUID());

        var result = handler.handle(countOp(sessionId), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(countingQuery);
    }

    @Test
    void handle_countWithoutAuthentication_isRejectedForbidden() {
        SyncTestAuth.clear();

        var result = handler.handle(countOp(UUID.randomUUID()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(countingCommand, never()).saveCount(any(), any());
    }

    // ── v1s-15-9: integral guard ──────────────────────────────────────

    private SyncOperation countWith(Object theoretical, Object physical) {
        return new SyncOperation("op-int", "SAVE_INVENTORY_COUNT", "entity-1",
                Map.of("sessionId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "productName", "Savon",
                        "theoretical", theoretical,
                        "physical", physical),
                Instant.now());
    }

    @Test
    void handle_fractionalPhysical_isRejectedNotTruncated() {
        var result = handler.handle(countWith(50, 47.5), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid physical");
        verifyNoInteractions(countingCommand);
    }

    @Test
    void handle_outOfRangeTheoretical_isRejected() {
        var result = handler.handle(countWith(3e10, 47), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid theoretical");
        verifyNoInteractions(countingCommand);
    }
}
