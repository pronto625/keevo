package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SaleSyncHandler extends AbstractSyncOperationHandler {

    private final RecordSaleUseCase recordSaleUseCase;

    public SaleSyncHandler(RecordSaleUseCase recordSaleUseCase) {
        this.recordSaleUseCase = recordSaleUseCase;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_SALE");
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        requireNonNull(p, "saleId");
        requireNonNull(p, "storeId");
        requireNonNull(p, "paymentMode");
        requireList(p, "items");
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();

        UUID saleId = UUID.fromString((String) p.get("saleId"));
        UUID storeId = UUID.fromString((String) p.get("storeId"));
        UUID clientId = p.get("clientId") != null ? UUID.fromString((String) p.get("clientId")) : null;
        PaymentMode paymentMode = PaymentMode.valueOf((String) p.get("paymentMode"));
        String mobileMoneyRef = (String) p.get("mobileMoneyRef");
        int discountAmount = p.get("discountAmount") != null ? ((Number) p.get("discountAmount")).intValue() : 0;

        String requestedStatusStr = (String) p.get("requestedStatus");
        SaleStatus requestedStatus = requestedStatusStr != null ? SaleStatus.valueOf(requestedStatusStr) : null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
        List<SaleItemCommand> saleItems = items.stream().map(item -> new SaleItemCommand(
                item.get("itemId") != null ? UUID.fromString((String) item.get("itemId")) : null,
                UUID.fromString((String) item.get("productId")),
                item.get("variantId") != null ? UUID.fromString((String) item.get("variantId")) : null,
                (String) item.get("productName"),
                ((Number) item.get("catalogueUnitPrice")).intValue(),
                ((Number) item.get("appliedUnitPrice")).intValue(),
                ((Number) item.get("quantity")).intValue()
        )).toList();

        recordSaleUseCase.recordSale(new RecordSaleCommand(
                saleId, actorId, storeId, clientId, paymentMode, mobileMoneyRef,
                discountAmount, requestedStatus, saleItems));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }

    private void requireNonNull(Map<String, Object> payload, String key) {
        if (!payload.containsKey(key) || payload.get(key) == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }

    private void requireList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            throw new IllegalArgumentException("Missing or empty required list: " + key);
        }
    }
}
