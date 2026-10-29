package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.CountingCommandPort;
import com.keevo.inventory.api.CountingQueryPort;
import com.keevo.inventory.api.SaveCountInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * InventoryCountSyncHandler — Handles SAVE_INVENTORY_COUNT push operations.
 *
 * <p>Idempotent via upsert: delegates to the inventory counting port whose
 * repository upserts by (sessionId + productId + variantId).
 */
@Component
public class InventoryCountSyncHandler extends AbstractSyncOperationHandler {

    // 12.2b: inventory public API
    private final CountingCommandPort countingCommand;
    private final CountingQueryPort countingQuery;

    public InventoryCountSyncHandler(CountingCommandPort countingCommand,
                                     CountingQueryPort countingQuery) {
        this.countingCommand = countingCommand;
        this.countingQuery = countingQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("SAVE_INVENTORY_COUNT");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        requireNonNull(p, "sessionId");
        requireNonNull(p, "productId");
        requireNonNull(p, "productName");
        requireNonNull(p, "theoretical");
        requireNonNull(p, "physical");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        UUID sessionId = UUID.fromString((String) p.get("sessionId"));

        // v1s-12-10: an EMPLOYEE only counts in a session of their assigned store.
        // Unknown session → left to the use case (existing not-found error).
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null) {
            countingQuery.findSessionStoreId(tenantId, sessionId).ifPresent(sessionStoreId -> {
                if (!assignedStoreId.equals(sessionStoreId)) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Vous ne pouvez compter que dans un inventaire de votre boutique");
                }
            });
        }

        UUID productId = UUID.fromString((String) p.get("productId"));
        UUID variantId = p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null;
        String productName = (String) p.get("productName");
        String variantLabel = (String) p.get("variantLabel");
        int theoretical = requireIntegralInt(p.get("theoretical"), "theoretical");
        int physical = requireIntegralInt(p.get("physical"), "physical");

        // Keep the device's count id so the pull updates its own row instead of adding one.
        var input = new SaveCountInput(
                sessionId, productId, variantId,
                productName, variantLabel,
                theoretical, physical, actorId,
                parseUuid(operation.entityId()));

        UUID savedId = countingCommand.saveCount(tenantId, input);

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                savedId.toString(), null);
    }

    private void requireNonNull(Map<String, Object> payload, String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }
}
