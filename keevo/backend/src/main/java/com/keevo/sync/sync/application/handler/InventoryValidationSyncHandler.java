package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.inventory.api.CountingCommandPort;
import com.keevo.inventory.api.CountingQueryPort;
import com.keevo.inventory.api.ValidateCountingInput;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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

    // 12.2b: inventory public API
    private final CountingCommandPort countingCommand;
    private final CountingQueryPort countingQuery;

    public InventoryValidationSyncHandler(CountingCommandPort countingCommand,
                                          CountingQueryPort countingQuery) {
        this.countingCommand = countingCommand;
        this.countingQuery = countingQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("VALIDATE_INVENTORY");
    }

    @Override
    public String moduleId() {
        return "inventory";
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (!p.containsKey("sessionId") || p.get("sessionId") == null) {
            throw new IllegalArgumentException("Missing required field: sessionId");
        }
    }

    /**
     * Local ADJUSTMENT movement ids sent by the device ({"productId|variantId": movementId}).
     * Invalid entries are ignored: the server then generates the id, as before.
     */
    private static Map<String, UUID> movementIds(Map<String, Object> payload) {
        Map<String, UUID> ids = new HashMap<>();
        if (payload.get("movementIds") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> {
                UUID id = parseUuid(value);
                if (key instanceof String k && id != null) ids.put(k, id);
            });
        }
        return ids;
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Mirror of InventorySessionController validate (@PreAuthorize OWNER):
        // validation rewrites stock with the counted quantities.
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can validate an inventory");
        }
        UUID sessionId = UUID.fromString((String) operation.payload().get("sessionId"));

        // Idempotency guard: if session already VALIDATED, skip
        if (countingQuery.isSessionValidated(tenantId, sessionId)) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    sessionId.toString(), null);
        }

        UUID validatedId = countingCommand.validate(tenantId,
                new ValidateCountingInput(sessionId, actorId, movementIds(operation.payload())));
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                validatedId.toString(), null);
    }
}
