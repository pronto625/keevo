package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * StoreSyncHandler — replays UPDATE_STORE / DEACTIVATE_STORE operations queued
 * by the Flutter client while offline (Story 16.6).
 *
 * <p>Delegates to the existing {@code UpdateStoreUseCase} / {@code DeactivateStoreUseCase},
 * which already enforce business rules (store existence, plan limits) synchronously.
 *
 * <p>v1s-13-8: state-based idempotency — a replayed DEACTIVATE on an inactive store, or an
 * UPDATE whose values already match the stored ones, is an APPLIED no-op (the use case is not
 * called, so no second domain event). A missing store still reaches the use case, which
 * throws STORE_NOT_FOUND.
 */
@Component
public class StoreSyncHandler extends AbstractSyncOperationHandler {

    private final UpdateStoreUseCase updateStoreUseCase;
    private final DeactivateStoreUseCase deactivateStoreUseCase;
    private final StoreRepository storeRepository;

    public StoreSyncHandler(UpdateStoreUseCase updateStoreUseCase,
                            DeactivateStoreUseCase deactivateStoreUseCase,
                            StoreRepository storeRepository) {
        this.updateStoreUseCase = updateStoreUseCase;
        this.deactivateStoreUseCase = deactivateStoreUseCase;
        this.storeRepository = storeRepository;
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
        var current = storeRepository.findById(storeId);

        return switch (operation.operationType()) {
            case "UPDATE_STORE" -> {
                String name = (String) p.get("name");
                String address = (String) p.get("address");
                String phone = (String) p.get("phone");
                if (current.isPresent()
                        && name != null
                        && Objects.equals(name.trim(), current.get().name())
                        && Objects.equals(address, current.get().address())
                        && Objects.equals(phone, current.get().phone())) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            storeId.toString(), null);
                }
                var store = updateStoreUseCase.execute(new UpdateStoreCommand(
                        storeId,
                        name,
                        address,
                        phone,
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        store.id().toString(), null);
            }
            case "DEACTIVATE_STORE" -> {
                if (current.isPresent() && !current.get().isActive()) {
                    yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            storeId.toString(), null);
                }
                deactivateStoreUseCase.execute(new DeactivateStoreCommand(storeId, actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        storeId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
