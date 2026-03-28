package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryCommand;
import com.keevo.inventory.counting.domain.port.in.ValidateInventoryUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles VALIDATE_INVENTORY sync operations pushed from offline clients.
 * Idempotent: if session is already VALIDATED, returns APPLIED (no-op).
 * Story 6.4.
 */
@Component
public class InventoryValidationSyncHandler extends AbstractSyncOperationHandler {

    private final ValidateInventoryUseCase validateUseCase;
    private final InventorySessionRepository sessionRepo;

    public InventoryValidationSyncHandler(ValidateInventoryUseCase validateUseCase,
                                          InventorySessionRepository sessionRepo) {
        this.validateUseCase = validateUseCase;
        this.sessionRepo = sessionRepo;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("VALIDATE_INVENTORY");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (!p.containsKey("sessionId") || p.get("sessionId") == null) {
            throw new IllegalArgumentException("Missing required field: sessionId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        UUID sessionId = UUID.fromString((String) operation.payload().get("sessionId"));

        // Idempotency guard: if session already VALIDATED, skip
        var sessionOpt = sessionRepo.findById(sessionId);
        if (sessionOpt.isPresent() && sessionOpt.get().getStatus() == InventorySessionStatus.VALIDATED) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    sessionId.toString(), null);
        }

        var result = validateUseCase.execute(new ValidateInventoryCommand(sessionId, actorId));
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                result.sessionId().toString(), null);
    }
}
