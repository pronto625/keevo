package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * StoreSyncHandler — replays UPDATE_STORE / DEACTIVATE_STORE operations queued
 * by the Flutter client while offline (Story 16.6).
 *
 * <p>Delegates to the existing {@code UpdateStoreUseCase} / {@code DeactivateStoreUseCase},
 * which already enforce business rules (store existence, plan limits) synchronously.
 */
@Component
public class StoreSyncHandler extends AbstractSyncOperationHandler {

    private final UpdateStoreUseCase updateStoreUseCase;
    private final DeactivateStoreUseCase deactivateStoreUseCase;

    public StoreSyncHandler(UpdateStoreUseCase updateStoreUseCase,
                            DeactivateStoreUseCase deactivateStoreUseCase) {
        this.updateStoreUseCase = updateStoreUseCase;
        this.deactivateStoreUseCase = deactivateStoreUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_STORE", "DEACTIVATE_STORE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        if (operation.payload().get("storeId") == null) {
            throw new IllegalArgumentException("Missing required field: storeId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Story v1s-12-8 / v1s-16-6 AC8: OWNER-only guard (mirror StoreController.requireOwner())
        if (!isOwnerRole()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only OWNER can manage stores");
        }

        Map<String, Object> p = operation.payload();
        UUID storeId = UUID.fromString((String) p.get("storeId"));

        return switch (operation.operationType()) {
            case "UPDATE_STORE" -> {
                var store = updateStoreUseCase.execute(new UpdateStoreCommand(
                        storeId,
                        (String) p.get("name"),
                        (String) p.get("address"),
                        (String) p.get("phone"),
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        store.id().toString(), null);
            }
            case "DEACTIVATE_STORE" -> {
                deactivateStoreUseCase.execute(new DeactivateStoreCommand(storeId, actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        storeId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
