package com.keevo.commerce.api;

/**
 * Sale writes for other modules (Story 12.2c). Each call runs the commerce use case unchanged —
 * same checks, stock movements, events and error codes. The use cases are REQUIRED: a call joins
 * the caller's transaction (opening one only when there is none), so the sale and its stock
 * movements stay atomic.
 */
public interface SaleCommandPort {

    /**
     * Records a sale (idempotent on {@code saleId}).
     *
     * @throws com.keevo.shared.domain.exception.DomainException e.g. INSUFFICIENT_STOCK when
     *         {@code allowNegativeStock} is false and a line exceeds the available stock
     */
    void recordSale(RecordSaleInput input);

    /**
     * Force-validates a PENDING_VALIDATION sale.
     *
     * @throws com.keevo.shared.domain.exception.DomainException SALE_NOT_FOUND, SALE_NOT_PENDING,
     *         FORBIDDEN (store scope), VALIDATION_ERROR (justification)
     */
    void validateSale(ValidateSaleInput input);

    /**
     * Cancels a sale (pending, or completed with stock restoration unless {@code pendingOnly}).
     *
     * @throws com.keevo.shared.domain.exception.DomainException SALE_NOT_FOUND, SALE_ALREADY_CANCELLED,
     *         FORBIDDEN
     */
    void cancelSale(CancelSaleInput input);
}
