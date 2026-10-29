package com.keevo.commerce.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a sale published by {@code commerce :: api} (Story 12.2c) — never the aggregate.
 *
 * @param employeeId  the seller
 * @param clientId    nullable
 * @param totalAmount XAF, discount deducted
 * @param occurredAt  when the sale was made
 * @param createdAt   when the server recorded it
 */
public record SaleView(
        UUID id,
        UUID storeId,
        UUID employeeId,
        UUID clientId,
        SalePaymentMode paymentMode,
        SaleState status,
        int totalAmount,
        int discountAmount,
        Instant occurredAt,
        Instant createdAt,
        List<SaleItemView> items
) {
    public SaleView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
