package com.keevo.catalog.stock.domain.service;

import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;

/**
 * TransferValidationStrategy — GoF Strategy.
 * Validates a transfer command before execution.
 * Closed for modification, open for extension.
 * Story 3.3.
 */
public interface TransferValidationStrategy {
    /**
     * Validate the command. Throws {@link com.keevo.shared.domain.exception.DomainException}
     * on any violation.
     *
     * @param command          the transfer command to validate
     * @param availableSourceQty current stock at source (0 if no stock level exists)
     */
    void validate(TransferStockCommand command, int availableSourceQty);
}
