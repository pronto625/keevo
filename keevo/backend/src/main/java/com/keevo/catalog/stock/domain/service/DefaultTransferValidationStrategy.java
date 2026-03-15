package com.keevo.catalog.stock.domain.service;

import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DefaultTransferValidationStrategy — owner-level transfer validation.
 *
 * <p>Rules:
 * 1. sourceStoreId != destinationStoreId  → SAME_SOURCE_DESTINATION
 * 2. availableSourceQty >= quantity       → INSUFFICIENT_STOCK with details
 *
 * <p>Store active status is checked upstream in ExecuteTransferService.
 * Story 3.3.
 */
@Component
public class DefaultTransferValidationStrategy implements TransferValidationStrategy {

    @Override
    public void validate(TransferStockCommand command, int availableSourceQty) {
        if (command.sourceStoreId().equals(command.destinationStoreId())) {
            throw new DomainException(ErrorCode.SAME_SOURCE_DESTINATION,
                "Source and destination stores must differ");
        }
        if (availableSourceQty < command.quantity()) {
            throw new DomainException(ErrorCode.INSUFFICIENT_STOCK,
                "Insufficient stock: available=" + availableSourceQty
                    + " requested=" + command.quantity(),
                Map.of("available", availableSourceQty, "requested", command.quantity()));
        }
    }
}
