package com.keevo.sync.sync.application.handler;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class StockAdjustSyncHandler extends AbstractSyncOperationHandler {

    /**
     * Story 17.11 — a {@code RECORD_STOCK_ENTRY} whose movement id is already used by a DIFFERENT movement
     * (other product / store / variant / type / quantity). Stable reason the client can act on.
     */
    public static final String MOVEMENT_ID_ALREADY_USED = "MOVEMENT_ID_ALREADY_USED";

    private final StockCommandPort stockCommand; // 12.2b: inventory public API
    private final StockQueryPort stockQuery;

    public StockAdjustSyncHandler(StockCommandPort stockCommand, StockQueryPort stockQuery) {
        this.stockCommand = stockCommand;
        this.stockQuery = stockQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
    }

    @Override
    public String moduleId() {
        return "inventory";
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("productId") == null || p.get("storeId") == null || p.get("quantity") == null) {
            throw new IllegalArgumentException("Missing required stock fields");
        }
        requireIntegralInt(p.get("quantity"), "quantity");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID productId = UUID.fromString((String) p.get("productId"));
        UUID variantId = p.get("variantId") != null ? UUID.fromString((String) p.get("variantId")) : null;
        UUID storeId = UUID.fromString((String) p.get("storeId"));
        int quantity = requireIntegralInt(p.get("quantity"), "quantity");
        String notes = (String) p.get("notes");

        // Story v1s-12-8 AC3: mirror StockController scope check (FR36)
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "EMPLOYEE cannot modify stock for another store");
        }

        // The device queues its local movement id as entityId: reusing it lets the pull update
        // that movement instead of adding a second copy to the history. Some callers queue the
        // product id instead, which is not a movement id.
        UUID movementId = parseUuid(operation.entityId());
        if (productId.equals(movementId)) movementId = null;

        if ("RECORD_STOCK_ENTRY".equals(operation.operationType())) {
            // 17.11: the same entry re-queued under a NEW operation id but the same movement id would hit the
            // stock_movements primary key (INTERNAL_ERROR). Same content → DUPLICATE (no second entry);
            // different content → a stable rejection.
            if (movementId != null) {
                var existing = stockQuery.findMovement(tenantId, movementId);
                if (existing.isPresent()) {
                    var m = existing.get();
                    boolean same = productId.equals(m.productId()) && storeId.equals(m.storeId())
                            && java.util.Objects.equals(variantId, m.variantId())
                            && "STOCK_ENTRY".equals(m.movementType()) && m.quantityChange() == quantity;
                    return new SyncOperationResult(operation.operationId(),
                            same ? SyncOperationStatus.DUPLICATE : SyncOperationStatus.REJECTED,
                            null, same ? null : MOVEMENT_ID_ALREADY_USED);
                }
            }
            stockCommand.recordEntry(tenantId, productId, variantId, storeId, quantity, actorId, notes, movementId);
        } else {
            stockCommand.adjustTo(tenantId, productId, variantId, storeId, quantity, actorId, notes, movementId);
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, null, null);
    }
}
