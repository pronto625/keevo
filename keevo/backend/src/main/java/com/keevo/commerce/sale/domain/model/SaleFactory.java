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
        return from(command, SaleStatus.COMPLETED);
    }

    public static Sale from(RecordSaleCommand command, SaleStatus statusOverride) {
        Instant now = Instant.now();
        // An offline sale keeps the time it was made so it lands in the right day's closure.
        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : now;
        List<SaleItem> items = command.items().stream()
                .map(itemCmd -> toSaleItem(command.saleId(), itemCmd))
                .toList();

        int subtotalSum = items.stream()
                .mapToInt(SaleItem::getSubtotal)
                .sum();

        int totalAmount = subtotalSum - command.discountAmount();

        return new Sale(
                command.saleId(),
                command.storeId(),
                command.actorId(),
                command.clientId(),
                command.paymentMode(),
                totalAmount,
                command.discountAmount(),
                statusOverride,
                occurredAt,
                now,
                items,
                0L,
                command.mobileMoneyRef()
        );
    }

    private static SaleItem toSaleItem(UUID saleId, SaleItemCommand cmd) {
        return new SaleItem(
                cmd.itemId() != null ? cmd.itemId() : UUID.randomUUID(),
                saleId,
                cmd.productId(),
                cmd.variantId(),
                cmd.productName(),
                cmd.catalogueUnitPrice(),
                cmd.appliedUnitPrice(),
                cmd.quantity()
        );
    }
}
