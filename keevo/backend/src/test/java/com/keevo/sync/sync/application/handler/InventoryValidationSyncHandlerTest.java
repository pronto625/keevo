package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryValidationSyncHandlerTest {

    @Mock ValidateInventoryUseCase validateUseCase;
    @Mock InventorySessionRepository sessionRepo;
    @InjectMocks InventoryValidationSyncHandler handler;

    @Test
    void supportedTypes_shouldReturnValidateInventory() {
        assertThat(handler.supportedTypes()).containsExactly("VALIDATE_INVENTORY");
    }

    @Test
    void apply_shouldCallValidateUseCase() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_test";

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(
                new InventorySession(sessionId, UUID.randomUUID(), InventoryScope.FULL, null,
                        InventorySessionStatus.IN_PROGRESS, actorId, Instant.now(),
                        null, null, null, Instant.now())));
        when(validateUseCase.execute(any())).thenReturn(
                new ValidateInventoryResult(sessionId, 3, InventorySessionStatus.VALIDATED, Instant.now()));

        var op = new SyncOperation("op1", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString()), Instant.now());

        var result = handler.handle(op, actorId, tenantId);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(validateUseCase).execute(any(ValidateInventoryCommand.class));
    }

    @Test
    void apply_alreadyValidated_shouldReturnAppliedNoOp() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(
                new InventorySession(sessionId, UUID.randomUUID(), InventoryScope.FULL, null,
                        InventorySessionStatus.VALIDATED, actorId, Instant.now(),
                        null, null, Instant.now(), Instant.now())));

        var op = new SyncOperation("op2", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString()), Instant.now());

        var result = handler.handle(op, actorId, "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(validateUseCase, never()).execute(any());
    }

    @Test
    void validate_missingSessionId_shouldThrow() {
        var op = new SyncOperation("op3", "VALIDATE_INVENTORY", "xxx",
                Map.of(), Instant.now());

        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }
}
