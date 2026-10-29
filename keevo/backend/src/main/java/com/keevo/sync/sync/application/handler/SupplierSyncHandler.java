package com.keevo.sync.sync.application.handler;

import com.keevo.crm.api.SupplierCommandPort;
import com.keevo.crm.api.UpdateSupplierInput;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SupplierSyncHandler extends AbstractSyncOperationHandler {

    // 12.2a: crm reached only through its public API.
    private final SupplierCommandPort supplierCommand;

    public SupplierSyncHandler(SupplierCommandPort supplierCommand) {
        this.supplierCommand = supplierCommand;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER", "UNARCHIVE_SUPPLIER");
    }

    @Override
    public String moduleId() {
        return "crm";
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
                var supplier = supplierCommand.update(tenantId, new UpdateSupplierInput(
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
                supplierCommand.archive(tenantId, supplierId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        supplierId.toString(), null);
            }
            case "UNARCHIVE_SUPPLIER" -> {
                // Same access rules as ARCHIVE_SUPPLIER (no role restriction).
                supplierCommand.unarchive(tenantId, supplierId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        supplierId.toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }
}
