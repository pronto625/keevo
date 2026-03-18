package com.keevo.commerce.sale.adapter.in.rest.dto;

import com.keevo.commerce.sale.domain.model.Sale;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PendingSaleResponseDto(
        UUID id,
        UUID storeId,
        UUID employeeId,
        String status,
        int totalAmount,
        int discountAmount,
        Instant createdAt,
        List<ItemDto> items
) {

    public record ItemDto(
            UUID productId,
            String productName,
            int appliedUnitPrice,
            int quantity,
            int subtotal
    ) {}

    public static PendingSaleResponseDto from(Sale sale) {
        return new PendingSaleResponseDto(
                sale.getId(),
                sale.getStoreId(),
                sale.getEmployeeId(),
                sale.getStatus().name(),
                sale.getTotalAmount(),
                sale.getDiscountAmount(),
                sale.getCreatedAt(),
                sale.getItems().stream()
                        .map(i -> new ItemDto(
                                i.getProductId(),
                                i.getProductName(),
                                i.getAppliedUnitPrice(),
                                i.getQuantity(),
                                i.getSubtotal()))
                        .toList()
        );
    }
}
