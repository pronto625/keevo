package com.keevo.commerce.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Map;
import java.util.UUID;

/**
 * Manual validation of a pending sale (Story 12.2c).
 *
 * @param assignedStoreId     store of an EMPLOYEE actor (scope check) — null for an OWNER
 * @param productIdRemappings draft product id → promoted product id, applied to the sale's items
 *                            before validation — nullable
 * @param initialStockEntries product id → initial stock entered for a promoted draft — nullable
 *                            (both maps are copied immutably; null keys/values are rejected)
 */
public record ValidateSaleInput(
        UUID saleId,
        UUID actorId,
        UUID assignedStoreId,
        String justification,
        Map<UUID, UUID> productIdRemappings,
        Map<UUID, Integer> initialStockEntries
) {
    public ValidateSaleInput {
        if (saleId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Sale id is required");
        }
        // defensive, immutable copies (null stays null — "nothing to remap / seed")
        productIdRemappings = productIdRemappings != null ? Map.copyOf(productIdRemappings) : null;
        initialStockEntries = initialStockEntries != null ? Map.copyOf(initialStockEntries) : null;
    }
}
