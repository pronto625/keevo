package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.*;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void setUp() {
        SyncTestAuth.owner();
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    // Validation overwrites stock with the counted quantities; it is OWNER-only
    // over REST (@PreAuthorize) and must stay so through the sync push.
    @Test
    void apply_employeeActor_isForbidden() {
        SyncTestAuth.employee(UUID.randomUUID());
        UUID sessionId = UUID.randomUUID();
        var op = new SyncOperation("op-emp", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString()), Instant.now());

        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(validateUseCase);
    }

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
    void apply_passesTheDeviceMovementIds_ignoringInvalidOnes() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(
                new InventorySession(sessionId, UUID.randomUUID(), InventoryScope.FULL, null,
                        InventorySessionStatus.IN_PROGRESS, actorId, Instant.now(),
                        null, null, null, Instant.now())));
        when(validateUseCase.execute(any())).thenReturn(
                new ValidateInventoryResult(sessionId, 1, InventorySessionStatus.VALIDATED, Instant.now()));

        String key = ValidateInventoryCommand.movementKey(productId, null);
        var op = new SyncOperation("op1", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString(),
                        "movementIds", Map.of(key, movementId.toString(), "other|", "not-a-uuid")),
                Instant.now());

        handler.handle(op, actorId, "kv_test");

        var captor = org.mockito.ArgumentCaptor.forClass(ValidateInventoryCommand.class);
        verify(validateUseCase).execute(captor.capture());
        assertThat(captor.getValue().movementIds()).containsExactly(Map.entry(key, movementId));
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
