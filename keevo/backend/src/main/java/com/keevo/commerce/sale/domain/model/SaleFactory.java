package com.keevo.commerce.sale.domain.model;

import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaleFactory — GoF Factory Method. Creates Sale aggregate + SaleItem list
 * from a RecordSaleCommand.
 */
public final class SaleFactory {

    private SaleFactory() {}

    public static Sale from(RecordSaleCommand command) {
        Instant now = Instant.now();
        List<SaleItem> items = command.items().stream()
                .map(itemCmd -> toSaleItem(command.saleId(), itemCmd))
                .toList();

        int totalAmount = items.stream()
                .mapToInt(SaleItem::getSubtotal)
                .sum();

        return new Sale(
                command.saleId(),
                command.storeId(),
                command.actorId(),
                command.clientId(),
                command.paymentMode(),
                totalAmount,
                SaleStatus.COMPLETED,
                now,
                now,
                items
        );
    }

    private static SaleItem toSaleItem(UUID saleId, SaleItemCommand cmd) {
        return new SaleItem(
                UUID.randomUUID(),
                saleId,
                cmd.productId(),
                cmd.variantId(),
                cmd.productName(),
                cmd.appliedUnitPrice(),
                cmd.quantity()
        );
    }
}
