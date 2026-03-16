package com.keevo.commerce.sale.domain.port.in;

import com.keevo.commerce.sale.domain.model.PaymentMode;

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
            List<SaleItemCommand> items
    ) {}

    record SaleItemCommand(
            UUID productId,
            UUID variantId,   // nullable
            String productName,
            int appliedUnitPrice,
            int quantity
    ) {}

    void recordSale(RecordSaleCommand command);
}
