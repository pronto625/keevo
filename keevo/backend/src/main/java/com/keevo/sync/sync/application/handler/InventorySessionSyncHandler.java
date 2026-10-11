package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * InventorySessionSyncHandler — Handles CREATE_INVENTORY_SESSION push operations.
 *
 * <p>Delegates to {@link CreateInventorySessionUseCase} with the client session id
 * (v1s-13-8). Idempotent by session ID: if the session already exists, the use case
 * returns it without side effects and the operation is marked APPLIED (no-op).
 */
@Component
public class InventorySessionSyncHandler extends AbstractSyncOperationHandler {

    private final CreateInventorySessionUseCase createInventorySessionUseCase;

    public InventorySessionSyncHandler(CreateInventorySessionUseCase createInventorySessionUseCase) {
        this.createInventorySessionUseCase = createInventorySessionUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_INVENTORY_SESSION");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        requireNonNull(p, "sessionId");
        requireNonNull(p, "storeId");
        requireNonNull(p, "scope");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        UUID sessionId = UUID.fromString((String) p.get("sessionId"));
        UUID storeId = UUID.fromString((String) p.get("storeId"));

        // v1s-12-10: an EMPLOYEE only opens sessions in their assigned store (mirror of
        // InventorySessionController.createSession). OWNER (null) stays unscoped; null auth
        // fails closed. Checked before the use case (and its idempotency lookup) — no
        // read-back of foreign data.
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Vous ne pouvez démarrer un inventaire que dans votre boutique");
        }

        InventoryScope scope = InventoryScope.valueOf((String) p.get("scope"));

        @SuppressWarnings("unchecked")
        List<String> categoryIdStrings = (List<String>) p.get("categoryIds");
        List<UUID> categoryIds = categoryIdStrings != null
                ? categoryIdStrings.stream().map(UUID::fromString).toList()
                : null;

        // v1s-13-8: route through the use case (REST parity) with the client session id —
        // replay of an existing id is a no-op inside the use case (no 2nd row/event); store
        // existence, single-active-session and category rules surface as DomainException →
        // REJECTED with their error code.
        InventorySession session = createInventorySessionUseCase.execute(
                new CreateInventorySessionCommand(sessionId, storeId, scope, categoryIds, actorId));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                session.getId().toString(), null);
    }

    private void requireNonNull(Map<String, Object> payload, String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }
}
