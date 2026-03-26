package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryCountSyncHandlerTest {

    @Mock private SaveInventoryCountUseCase saveInventoryCountUseCase;

    private InventoryCountSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new InventoryCountSyncHandler(saveInventoryCountUseCase);
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
}
