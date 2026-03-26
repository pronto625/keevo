package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.SaleStatus;

import java.util.List;
import java.util.UUID;

/**
 * RecordSaleUseCase — input port for recording a sale.
 * MCP-pure: only Java primitives/records. No framework types.
 */
public interface RecordSaleUseCase {

    record RecordSaleCommand(
            UUID saleId,           // client-generated for idempotency
            UUID actorId,          // from JWT claim
            UUID storeId,          // from JWT claim — must match employee's store
            UUID clientId,         // nullable
            PaymentMode paymentMode,
            String mobileMoneyRef, // nullable
            int discountAmount,    // Story 4.2 — 0 if no discount
            SaleStatus requestedStatus, // Story 4.3 — null or PENDING_VALIDATION
            List<SaleItemCommand> items
    ) {
        /** Backward-compatible constructor: requestedStatus defaults to null (→ COMPLETED). */
        public RecordSaleCommand(UUID saleId, UUID actorId, UUID storeId, UUID clientId,
                                 PaymentMode paymentMode, String mobileMoneyRef, int discountAmount,
                                 List<SaleItemCommand> items) {
            this(saleId, actorId, storeId, clientId, paymentMode, mobileMoneyRef,
                    discountAmount, null, items);
        }
    }

    record SaleItemCommand(
            UUID itemId,      // client-generated — nullable (server generates if absent)
            UUID productId,
            UUID variantId,   // nullable
            String productName,
            int catalogueUnitPrice,  // Story 4.2 — original catalogue price
            int appliedUnitPrice,    // may differ if overridden
            int quantity
    ) {}

    void recordSale(RecordSaleCommand command);
}
