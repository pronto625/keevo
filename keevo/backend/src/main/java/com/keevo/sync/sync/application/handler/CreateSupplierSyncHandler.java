package com.keevo.sync.sync.application.handler;

import com.keevo.crm.api.CreateSupplierInput;
import com.keevo.crm.api.SupplierCommandPort;
import com.keevo.crm.api.SupplierQueryPort;
import com.keevo.crm.api.SupplierView;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CreateSupplierSyncHandler — persists a supplier with the UUID generated on-device (Story 5.6 AC9).
 *
 * <p>Idempotent: if a supplier with the same UUID already exists the operation is
 * returned as APPLIED without creating a duplicate.
 *
 * <p>Bypasses {@code CreateSupplierUseCase} intentionally — preserves the client-assigned UUID
 * required for offline-first idempotency.
 */
@Component
public class CreateSupplierSyncHandler extends AbstractSyncOperationHandler {

    // 12.2a: crm reached only through its public API.
    private final SupplierQueryPort supplierQuery;
    private final SupplierCommandPort supplierCommand;

    public CreateSupplierSyncHandler(SupplierQueryPort supplierQuery, SupplierCommandPort supplierCommand) {
        this.supplierQuery = supplierQuery;
        this.supplierCommand = supplierCommand;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_SUPPLIER");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("id") == null)
            throw new IllegalArgumentException("Missing required field: id");
        if (p.get("name") == null || ((String) p.get("name")).isBlank())
            throw new IllegalArgumentException("Missing required field: name");
        // v1s-13-8: @NotBlank parity with the REST DTO — a blank phone is rejected.
        if (!(p.get("phone") instanceof String phone) || phone.isBlank())
            throw new IllegalArgumentException("Missing required field: phone");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID supplierId = UUID.fromString((String) p.get("id"));

        // Idempotency: if already synced, return APPLIED without duplicate insert.
        if (supplierQuery.existsById(supplierId)) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    supplierId.toString(), null);
        }

        // Product associations, when provided, are linked by the crm right after the insert.
        @SuppressWarnings("unchecked")
        List<String> rawIds = (List<String>) p.get("productIds");
        List<UUID> productIds = rawIds != null
                ? rawIds.stream().map(UUID::fromString).toList()
                : List.of();
        SupplierView saved = supplierCommand.createWithId(new CreateSupplierInput(
                supplierId,
                (String) p.get("name"),
                (String) p.get("phone"),
                (String) p.getOrDefault("email", null),
                productIds));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.id().toString(), null);
    }
}
