package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.catalog.contact.domain.port.out.SupplierRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private final SupplierRepository supplierRepository;

    public CreateSupplierSyncHandler(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
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
        if (p.get("phone") == null)
            throw new IllegalArgumentException("Missing required field: phone");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        UUID supplierId = UUID.fromString((String) p.get("id"));

        // Idempotency: if already synced, return APPLIED without duplicate insert.
        if (supplierRepository.findById(supplierId).isPresent()) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                    supplierId.toString(), null);
        }

        Instant now = Instant.now();
        Supplier supplier = new Supplier(
                supplierId,
                (String) p.get("name"),
                (String) p.get("phone"),
                (String) p.getOrDefault("email", null),
                false,
                now,
                now
        );
        Supplier saved = supplierRepository.save(supplier);

        // Link product associations when provided.
        @SuppressWarnings("unchecked")
        List<String> rawIds = (List<String>) p.get("productIds");
        if (rawIds != null && !rawIds.isEmpty()) {
            List<UUID> productIds = rawIds.stream().map(UUID::fromString).toList();
            supplierRepository.linkProducts(saved.id(), productIds);
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saved.id().toString(), null);
    }
}
