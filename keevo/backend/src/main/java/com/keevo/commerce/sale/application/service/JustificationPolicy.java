package com.keevo.commerce.sale.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

/**
 * JustificationPolicy — strict justification validation shared by the COMPLETED-sale
 * cancellation branch ({@link ValidateSaleService}) and correction ({@link CorrectSaleService}).
 *
 * <p>Deliberately separate from the permissive {@code validateJustification} used by the
 * PENDING flow — merging the two would silently change existing PENDING behavior (Story
 * v1s-13-5 Décision D3). Do not reuse this for PENDING paths.
 */
final class JustificationPolicy {

    private JustificationPolicy() {}

    static void requireStrict(String justification) {
        if (justification == null || justification.isBlank()) {
            throw new DomainException(ErrorCode.JUSTIFICATION_REQUIRED,
                    "Justification is required");
        }
        if (justification.trim().length() < 10) {
            throw new DomainException(ErrorCode.JUSTIFICATION_TOO_SHORT,
                    "Justification must be at least 10 characters");
        }
    }
}
