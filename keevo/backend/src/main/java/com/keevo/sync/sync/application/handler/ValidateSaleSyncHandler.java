package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ValidateSaleSyncHandler extends AbstractSyncOperationHandler {

    private final ValidateSaleUseCase validateSaleUseCase;

    public ValidateSaleSyncHandler(ValidateSaleUseCase validateSaleUseCase) {
        this.validateSaleUseCase = validateSaleUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("VALIDATE_SALE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("saleId") == null) {
            throw new IllegalArgumentException("Missing required field: saleId");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        UUID saleId = UUID.fromString((String) p.get("saleId"));
        String justification = (String) p.get("justification");

        Map<UUID, UUID> productIdRemappings = null;
        if (p.get("productIdRemappings") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            productIdRemappings = ((Map<String, String>) raw).entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> UUID.fromString(e.getKey()),
                            e -> UUID.fromString(e.getValue())));
        }

        Map<UUID, Integer> initialStockEntries = null;
        if (p.get("initialStockEntries") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            initialStockEntries = ((Map<String, Object>) raw).entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> UUID.fromString(e.getKey()),
                            e -> ((Number) e.getValue()).intValue()));
        }

        try {
            UUID assignedStoreId = extractAssignedStoreId();
            validateSaleUseCase.validateSale(
                    new ValidateSaleCommand(saleId, actorId, assignedStoreId, justification, productIdRemappings, initialStockEntries));
        } catch (DomainException e) {
            // Idempotency: if the sale was already completed (e.g. cascade-validated after
            // PROMOTE_PRODUCT), treat the operation as successfully applied rather than rejected.
            if (e.getErrorCode() == ErrorCode.SALE_NOT_PENDING) {
                return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        saleId.toString(), null);
            }
            throw e;
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }
}
