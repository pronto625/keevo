package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.CountingCommandPort;
import com.keevo.inventory.api.CountingQueryPort;
import com.keevo.inventory.api.ValidateCountingInput;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock CountingCommandPort validateUseCase;
    @Mock CountingQueryPort sessionRepo;
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

        when(sessionRepo.isSessionValidated("kv_test", sessionId)).thenReturn(false);
        when(validateUseCase.validate(any(), any())).thenReturn(sessionId);

        var op = new SyncOperation("op1", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString()), Instant.now());

        var result = handler.handle(op, actorId, tenantId);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(validateUseCase).validate(eq("kv_test"), any(ValidateCountingInput.class));
    }

    @Test
    void apply_passesTheDeviceMovementIds_ignoringInvalidOnes() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        when(sessionRepo.isSessionValidated("kv_test", sessionId)).thenReturn(false);
        when(validateUseCase.validate(any(), any())).thenReturn(sessionId);

        String key = productId + "|"; // "productId|variantId", no variant
        var op = new SyncOperation("op1", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString(),
                        "movementIds", Map.of(key, movementId.toString(), "other|", "not-a-uuid")),
                Instant.now());

        handler.handle(op, actorId, "kv_test");

        var captor = org.mockito.ArgumentCaptor.forClass(ValidateCountingInput.class);
        verify(validateUseCase).validate(eq("kv_test"), captor.capture());
        assertThat(captor.getValue().movementIds()).containsExactly(Map.entry(key, movementId));
    }

    @Test
    void apply_alreadyValidated_shouldReturnAppliedNoOp() {
        UUID sessionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(sessionRepo.isSessionValidated("kv_test", sessionId)).thenReturn(true);

        var op = new SyncOperation("op2", "VALIDATE_INVENTORY", sessionId.toString(),
                Map.of("sessionId", sessionId.toString()), Instant.now());

        var result = handler.handle(op, actorId, "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(validateUseCase, never()).validate(any(), any());
    }

    @Test
    void validate_missingSessionId_shouldThrow() {
        var op = new SyncOperation("op3", "VALIDATE_INVENTORY", "xxx",
                Map.of(), Instant.now());

        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }
}
