package com.keevo.sync.sync.application.handler;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.commerce.api.CancelSaleInput;
import com.keevo.commerce.api.SaleCommandPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.ActorPermissions;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CancelSaleSyncHandler extends AbstractSyncOperationHandler {

    /** Story 22.19 (D18) — « Annuler une vente du jour » (MANAGER by default, grantable to a custom role). */
    public static final String CANCEL_PERMISSION = "SALE_CANCEL";
    /** Story 22.19 (D18) — the justification recorded when the till sends none (the audit needs one). */
    static final String DEFAULT_JUSTIFICATION = "Annulation de la vente depuis la caisse";
    /** Story 22.19 (D18) — the till's short text (the REST answer keeps the V1 catalogue text). */
    static final String ALREADY_CANCELLED = "Vente déjà annulée";

    private final SaleCommandPort saleCommandPort;
    private final ActorPermissions permissions;

    public CancelSaleSyncHandler(SaleCommandPort saleCommandPort) {
        this(saleCommandPort, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CancelSaleSyncHandler(SaleCommandPort saleCommandPort, ActorPermissions permissions) {
        this.saleCommandPort = saleCommandPort;
        this.permissions = permissions;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CANCEL_SALE");
    }

    @Override
    public String moduleId() {
        return "pos";
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        if (p.get("saleId") == null) {
            throw new IllegalArgumentException("Missing required field: saleId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        // Story 22.19 (D18) — the owner, or a holder of « Annuler une vente du jour » (MANAGER by default, custom
        // roles that hold it); role read from the authenticated actor, never from the payload; fails closed.
        if (!isOwnerRole() && (permissions == null || !permissions.currentActorHolds(CANCEL_PERMISSION))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cancelling a sale is not allowed for this role");
        }
        Map<String, Object> p = operation.payload();

        UUID saleId = UUID.fromString((String) p.get("saleId"));
        String justification = (String) p.get("justification");
        if (justification == null || justification.isBlank()) {
            justification = DEFAULT_JUSTIFICATION;
        }

        // Story 22.19 (D18, B5) — a pending sale, or a COMPLETED sale of the same business day: stock restored and the
        // sale's automatic invoice cancelled by the invoicing module (credit note, on SaleCancelledEvent). A sale of
        // an earlier day → SALE_CANCEL_NOT_SAME_DAY; already cancelled → SALE_ALREADY_CANCELLED. A member only
        // cancels in his own store.
        UUID assignedStoreId = extractAssignedStoreId();
        saleCommandPort.cancelSale(tenantId,
                new CancelSaleInput(saleId, actorId, assignedStoreId, justification, false, true,
                        operation.clientTimestamp()));

        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                saleId.toString(), null);
    }

    /**
     * Story 22.19 (D18) — a refused cancellation carries its French reason in {@code conflictData.message} (« Une vente
     * ne s'annule que le jour même », « Vente déjà annulée »…), so the till shows it as is.
     */
    @Override
    protected SyncOperationResult mapDomainException(SyncOperation op, DomainException e) {
        SyncOperationResult mapped = super.mapDomainException(op, e);
        String message = "SALE_ALREADY_CANCELLED".equals(mapped.reason()) ? ALREADY_CANCELLED
                : com.keevo.shared.infrastructure.web.ErrorMessages.of(mapped.reason(), null);
        if (e.getUserMessage() != null) {
            message = e.getUserMessage();
        }
        if (message == null) {
            return mapped;
        }
        Map<String, Object> details = mapped.conflictData() == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(mapped.conflictData());
        details.put("message", message);
        return new SyncOperationResult(mapped.operationId(), mapped.status(), mapped.serverEntityId(),
                mapped.reason(), details);
    }
}
