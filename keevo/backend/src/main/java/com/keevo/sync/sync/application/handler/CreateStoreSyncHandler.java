package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.CreateStoreWithIdInput;
import com.keevo.store.api.StoreCommandPort;
import com.keevo.store.api.StoreType;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CreateStoreSyncHandler — persists a store with the UUID generated on-device (Story 16.6).
 *
 * <p>Idempotent: if a store with the same UUID already exists the operation is
 * returned as APPLIED without creating a duplicate.
 *
 * <p>Bypasses {@code CreateStoreUseCase} intentionally — the use case generates
 * its own {@code UUID.randomUUID()}, which would discard the client-assigned UUID
 * needed for offline-first idempotency. The store-limit lock, idempotency, plan-limit and
 * warehouse-uniqueness checks (mirroring {@code CreateStoreService}) are enforced by
 * {@code StoreCommandPort#createWithId} (store module, 12.2d) — code review
 * of v1s-16-6 found that, once this handler exists, a queued CREATE_STORE reaching
 * the backend without these checks would let an offline OWNER exceed the FREE-plan
 * store cap or create a second warehouse.
 */
@Component
public class CreateStoreSyncHandler extends AbstractSyncOperationHandler {

    private final StoreCommandPort storeCommand;

    public CreateStoreSyncHandler(StoreCommandPort storeCommand) {
        this.storeCommand = storeCommand;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_STORE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("id") == null)
            throw new IllegalArgumentException("Missing required field: id");
        if (!(p.get("name") == null || p.get("name") instanceof String))
            throw new IllegalArgumentException("Invalid field: name");
        // v1s-13-8: same rule as the REST DTO / StoreFactory (2–100 chars once trimmed)
        // → DomainException VALIDATION_ERROR → REJECTED.
        storeCommand.validateName((String) p.get("name"));
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Story v1s-12-8 / v1s-16-6 AC8: OWNER-only guard (mirror StoreController.requireOwner())
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage stores");
        }

        Map<String, Object> p = operation.payload();
        UUID storeId = UUID.fromString((String) p.get("id"));

        StoreType type = p.get("type") != null
                ? StoreType.valueOf((String) p.get("type"))
                : StoreType.STORE;

        // Lock, idempotency (existing id → APPLIED, no duplicate), plan-limit and warehouse checks, then
        // the insert — all in the store module (12.2d), inside SyncPushService's per-op transaction.
        storeCommand.createWithId(TenantContext.getCurrentTenant(), new CreateStoreWithIdInput(
                storeId,
                (String) p.get("name"),
                type,
                (String) p.getOrDefault("address", null),
                (String) p.getOrDefault("phone", null)));
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                storeId.toString(), null);
    }
}
