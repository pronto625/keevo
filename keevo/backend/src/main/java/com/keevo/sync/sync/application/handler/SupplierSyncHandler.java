package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.application.usecase.ArchiveSupplierUseCase;
import com.keevo.catalog.contact.application.usecase.UnarchiveSupplierUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateSupplierUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateSupplierUseCase.UpdateSupplierCommand;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SupplierSyncHandler extends AbstractSyncOperationHandler {

    private final UpdateSupplierUseCase updateSupplier;
    private final ArchiveSupplierUseCase archiveSupplier;
    private final UnarchiveSupplierUseCase unarchiveSupplier;

    public SupplierSyncHandler(UpdateSupplierUseCase updateSupplier, ArchiveSupplierUseCase archiveSupplier,
                             UnarchiveSupplierUseCase unarchiveSupplier) {
        this.updateSupplier = updateSupplier;
        this.archiveSupplier = archiveSupplier;
        this.unarchiveSupplier = unarchiveSupplier;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER", "UNARCHIVE_SUPPLIER");
    }

    @Override
    protected void validate(SyncOperation operation) {
        if (operation.payload().get("supplierId") == null) {
            throw new IllegalArgumentException("Missing required field: supplierId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID supplierId = UUID.fromString((String) p.get("supplierId"));

        return switch (operation.operationType()) {
            case "UPDATE_SUPPLIER" -> {
                var supplier = updateSupplier.execute(new UpdateSupplierCommand(
                        supplierId,
                        (String) p.get("name"),
                        (String) p.get("phone"),
                        (String) p.get("email"),
                        null, // productIds — not expected from sync
                        actorId));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        supplier.id().toString(), null);
            }
            case "ARCHIVE_SUPPLIER" -> {
                archiveSupplier.execute(supplierId, actorId, tenantId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        supplierId.toString(), null);
            }
            case "UNARCHIVE_SUPPLIER" -> {
                // Same access rules as ARCHIVE_SUPPLIER (no role restriction).
                unarchiveSupplier.execute(supplierId, actorId, tenantId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        supplierId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
