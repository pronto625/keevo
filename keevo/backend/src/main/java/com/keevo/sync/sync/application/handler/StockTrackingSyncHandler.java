package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.StockCommandPort;
import com.keevo.inventory.api.StockQueryPort;
import com.keevo.inventory.api.StockTrackingChange;
import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code SET_PRODUCT_STOCK_TRACKING} — Story 17.12: the owner turns a product's stock tracking on or off from an
 * offline device.
 *
 * <p>Payload: {@code {productId, tracked, initialQuantity?, storeId?, movementId?}}. Enabling with
 * {@code initialQuantity > 0} records one STOCK_ENTRY in {@code storeId} whose id is {@code movementId} (the local
 * movement), so the pull updates the device's own row. OWNER only ({@code FORBIDDEN} otherwise). Module
 * {@code inventory}: refused {@code MODULE_INACTIVE} by the push while it is off (the op stays queued).
 *
 * <p>Re-sent under a new operation id with a movement id already recorded: same content → {@code DUPLICATE};
 * different content → {@code REJECTED} / {@code MOVEMENT_ID_ALREADY_USED} (same rule as 17.11). A starting quantity
 * sent while the product was already tracked is not recorded: {@code APPLIED} with
 * {@code conflictData = {"initialEntry": "IGNORED"}} so the device drops its local movement and re-reads the levels.
 * Malformed ids ({@code productId}, {@code storeId}, {@code movementId}) → {@code REJECTED} {@code Invalid <field>: …}.
 */
@Component
public class StockTrackingSyncHandler extends AbstractSyncOperationHandler {

    public static final String OPERATION = "SET_PRODUCT_STOCK_TRACKING";
    /** {@code conflictData} of an APPLIED activation whose starting quantity was not recorded. */
    public static final Map<String, Object> INITIAL_ENTRY_IGNORED = Map.of("initialEntry", "IGNORED");

    private final StockCommandPort stockCommand;
    private final StockQueryPort stockQuery;

    public StockTrackingSyncHandler(StockCommandPort stockCommand, StockQueryPort stockQuery) {
        this.stockCommand = stockCommand;
        this.stockQuery = stockQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of(OPERATION);
    }

    @Override
    public String moduleId() {
        return "inventory";
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p == null || p.get("productId") == null) {
            throw new IllegalArgumentException("Missing required field: productId");
        }
        requireUuid(p.get("productId"), "productId");
        if (!(p.get("tracked") instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid tracked: must be a boolean");
        }
        optionalIntegralInt(p.get("initialQuantity"), "initialQuantity");
        if (p.get("storeId") != null) requireUuid(p.get("storeId"), "storeId");
        if (p.get("movementId") != null) requireUuid(p.get("movementId"), "movementId");
    }

    /** A UUID sent as a JSON string; anything else → {@code Invalid <field>: must be a UUID}. */
    static UUID requireUuid(Object raw, String field) {
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException("Invalid " + field + ": must be a UUID");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut modifier le suivi du stock d'un produit");
        }
        Map<String, Object> p = operation.payload();
        UUID productId = requireUuid(p.get("productId"), "productId");
        boolean tracked = (Boolean) p.get("tracked");
        Integer quantity = optionalIntegralInt(p.get("initialQuantity"), "initialQuantity");
        UUID storeId = p.get("storeId") != null ? requireUuid(p.get("storeId"), "storeId") : null;
        UUID movementId = p.get("movementId") != null ? requireUuid(p.get("movementId"), "movementId") : null;

        if (movementId != null && quantity != null && quantity > 0) {
            var existing = stockQuery.findMovement(tenantId, movementId);
            if (existing.isPresent()) {
                var m = existing.get();
                boolean same = productId.equals(m.productId()) && Objects.equals(storeId, m.storeId())
                        && m.variantId() == null && "STOCK_ENTRY".equals(m.movementType())
                        && m.quantityChange() == quantity;
                return new SyncOperationResult(operation.operationId(),
                        same ? SyncOperationStatus.DUPLICATE : SyncOperationStatus.REJECTED,
                        null, same ? null : StockAdjustSyncHandler.MOVEMENT_ID_ALREADY_USED);
            }
        }

        boolean entryRecorded = stockCommand.changeTracking(tenantId, new StockTrackingChange(
                productId, tracked, quantity, storeId, movementId, actorId, actorRole()));
        boolean entryIgnored = quantity != null && quantity > 0 && !entryRecorded;
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                productId.toString(), null, entryIgnored ? INITIAL_ENTRY_IGNORED : null);
    }
}
