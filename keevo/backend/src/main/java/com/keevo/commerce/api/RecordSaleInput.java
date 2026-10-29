package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sale recorded by another module (Story 12.2c — the sync push).
 *
 * @param saleId             client-generated id — a replay of an existing id is an idempotent no-op
 * @param clientId           nullable
 * @param mobileMoneyRef     nullable
 * @param discountAmount     0 when no discount
 * @param requestedStatus    null or {@link SaleState#COMPLETED} → completed; {@link SaleState#PENDING_VALIDATION}
 *                           → pending (no stock movement)
 * @param occurredAt         when the sale was made (offline push) — null → now
 * @param allowNegativeStock true only for an offline sale that already happened: applied even if
 *                           the server stock is short instead of failing with {@code INSUFFICIENT_STOCK}
 */
public record RecordSaleInput(
        UUID saleId,
        UUID actorId,
        UUID storeId,
        UUID clientId,
        SalePaymentMode paymentMode,
        String mobileMoneyRef,
        int discountAmount,
        SaleState requestedStatus,
        List<SaleLineInput> items,
        Instant occurredAt,
        boolean allowNegativeStock
) {
    public RecordSaleInput {
        if (saleId == null || storeId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale id and store id are required");
        }
        if (paymentMode == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale payment mode is required");
        }
        if (items == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale items are required");
        }
        items = List.copyOf(items);
    }
}
