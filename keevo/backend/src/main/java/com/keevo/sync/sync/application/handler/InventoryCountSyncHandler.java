package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
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
 * <p>Idempotent via upsert: delegates to SaveInventoryCountUseCase whose
 * repository upserts by (sessionId + productId + variantId).
 */
@Component
public class InventoryCountSyncHandler extends AbstractSyncOperationHandler {

    private final SaveInventoryCountUseCase saveInventoryCountUseCase;
    private final InventorySessionRepository sessionRepository;

    public InventoryCountSyncHandler(SaveInventoryCountUseCase saveInventoryCountUseCase,
                                     InventorySessionRepository sessionRepository) {
        this.saveInventoryCountUseCase = saveInventoryCountUseCase;
        this.sessionRepository = sessionRepository;
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
            sessionRepository.findById(sessionId).ifPresent(session -> {
                if (!assignedStoreId.equals(session.getStoreId())) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Vous ne pouvez compter que dans un inventaire de votre boutique");
                }
            });
        }

        UUID productId = UUID.fromString((String) p.get("productId"));
        UUID variantId = p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null;
        String productName = (String) p.get("productName");
        String variantLabel = (String) p.get("variantLabel");
        int theoretical = ((Number) p.get("theoretical")).intValue();
        int physical = ((Number) p.get("physical")).intValue();

        // Keep the device's count id so the pull updates its own row instead of adding one.
        var command = new SaveInventoryCountCommand(
                sessionId, productId, variantId,
                productName, variantLabel,
                theoretical, physical, actorId,
                parseUuid(operation.entityId()));

        InventoryCount saved = saveInventoryCountUseCase.execute(command);

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.getId().toString(), null);
    }

    private void requireNonNull(Map<String, Object> payload, String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }
}
