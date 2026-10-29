package com.keevo.sync.sync.application.handler;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.commerce.api.RecordSaleInput;
import com.keevo.commerce.api.SaleCommandPort;
import com.keevo.commerce.api.SaleLineInput;
import com.keevo.commerce.api.SalePaymentMode;
import com.keevo.commerce.api.SaleState;
import com.keevo.commerce.sale.domain.event.SaleDraftProductsUpgradedEvent;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class SaleSyncHandler extends AbstractSyncOperationHandler {

    private final SaleCommandPort saleCommandPort;
    private final ApplicationEventPublisher eventPublisher;
    private final StoreQueryPort storeQuery;

    public SaleSyncHandler(SaleCommandPort saleCommandPort,
                           ApplicationEventPublisher eventPublisher,
                           StoreQueryPort storeQuery) {
        this.saleCommandPort = saleCommandPort;
        this.eventPublisher = eventPublisher;
        this.storeQuery = storeQuery;
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
        // SaleController only lets an employee sell in the store of their token.
        // Rejected rather than rewritten: rewriting would decrement stock in a
        // store where the sale never happened.
        UUID assignedStoreId = extractAssignedStoreId();
        if (assignedStoreId != null && !assignedStoreId.equals(storeId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Employee cannot sell in another store");
        }
        // v1s-16-15: sales.store_id has no FK — a sale for a store the server does not
        // know (rolled back offline creation) would be stored as an orphan. Refused so the
        // device quarantines it and the payload lands in sync_error_log.
        if (storeQuery.findById(TenantContext.getCurrentTenant(), storeId).isEmpty()) {
            throw new DomainException(ErrorCode.STORE_NOT_FOUND, "Store not found: " + storeId);
        }
        UUID clientId = p.get("clientId") != null ? UUID.fromString((String) p.get("clientId")) : null;
        SalePaymentMode paymentMode = SalePaymentMode.valueOf((String) p.get("paymentMode"));
        String mobileMoneyRef = (String) p.get("mobileMoneyRef");
        int discountAmount = p.get("discountAmount") != null ? requireIntegralInt(p.get("discountAmount"), "discountAmount") : 0;

        SaleState requestedStatus = resolveRequestedStatus(p);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
        List<SaleLineInput> saleItems = items.stream().map(item -> new SaleLineInput(
                item.get("itemId") != null ? UUID.fromString((String) item.get("itemId")) : null,
                UUID.fromString((String) item.get("productId")),
                item.get("variantId") != null ? UUID.fromString((String) item.get("variantId")) : null,
                (String) item.get("productName"),
                requireIntegralInt(item.get("catalogueUnitPrice"), "catalogueUnitPrice"),
                requireIntegralInt(item.get("appliedUnitPrice"), "appliedUnitPrice"),
                requireIntegralInt(item.get("quantity"), "quantity")
        )).toList();

        saleCommandPort.recordSale(new RecordSaleInput(
                saleId, actorId, storeId, clientId, paymentMode, mobileMoneyRef,
                discountAmount, requestedStatus, saleItems,
                resolveClientTime(p.get("occurredAt"), operation.clientTimestamp()),
                true));

        // Notify owner when sale contained originally-draft products.
        @SuppressWarnings("unchecked")
        List<String> rawDraftIds = p.get("originalDraftProductIds") instanceof List<?>
                ? (List<String>) p.get("originalDraftProductIds")
                : List.of();
        if (!rawDraftIds.isEmpty()) {
            List<UUID> draftProductIds = rawDraftIds.stream().map(UUID::fromString).toList();
            int totalAmount = saleItems.stream()
                    .mapToInt(i -> i.appliedUnitPrice() * i.quantity())
                    .sum() - discountAmount;
            eventPublisher.publishEvent(new SaleDraftProductsUpgradedEvent(
                    saleId, storeId, actorId, draftProductIds, totalAmount, tenantId, Instant.now(),
                    isOwnerRole()));  // v1s-12-10: only an OWNER seller promotes drafts
        }

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }

    /**
     * v1s-13-8: explicit {@code requestedStatus} must be COMPLETED or PENDING_VALIDATION
     * (anything else → REJECTED). Payloads queued before the key was renamed (commit e694ae3)
     * carry {@code status}: used only when it is exactly one of those two values, otherwise
     * ignored (null → COMPLETED by default) so a stale sale is not rejected.
     */
    private static SaleState resolveRequestedStatus(Map<String, Object> p) {
        Object explicit = p.get("requestedStatus");
        if (explicit != null) {
            if ("COMPLETED".equals(explicit)) return SaleState.COMPLETED;
            if ("PENDING_VALIDATION".equals(explicit)) return SaleState.PENDING_VALIDATION;
            throw new IllegalArgumentException("Invalid requestedStatus: " + explicit);
        }
        Object legacy = p.get("status");
        if ("COMPLETED".equals(legacy)) return SaleState.COMPLETED;
        if ("PENDING_VALIDATION".equals(legacy)) return SaleState.PENDING_VALIDATION;
        return null;
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
