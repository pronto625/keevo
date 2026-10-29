package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * One line of a sale recorded through {@link SaleCommandPort#recordSale} (Story 12.2c).
 *
 * @param itemId             client-generated item id (also the SALE movement id) — null → server generates
 * @param variantId          null for a simple product
 * @param catalogueUnitPrice catalogue price at sale time
 * @param appliedUnitPrice   price actually charged (may differ when overridden)
 */
public record SaleLineInput(
        UUID itemId,
        UUID productId,
        UUID variantId,
        String productName,
        int catalogueUnitPrice,
        int appliedUnitPrice,
        int quantity
) {
    public SaleLineInput {
        if (productId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale line productId is required");
        }
    }
}
