package com.keevo.admin.catalog.adapter.in.rest.dto;

import com.keevo.admin.catalog.domain.model.AdminProductListItem;

import java.util.List;

public record AdminProductListItemDto(
        String id,
        String name,
        String tenantId,
        String tenantName,
        String tenantPlan,
        String categoryName,
        long price,
        int stockQuantity,
        String status,
        String updatedAt   // ISO-8601 string
) {
    public static AdminProductListItemDto from(AdminProductListItem item) {
        return new AdminProductListItemDto(
                item.id(),
                item.name(),
                item.tenantId(),
                item.tenantName(),
                item.tenantPlan(),
                item.categoryName(),
                item.price(),
                item.stockQuantity(),
                item.status(),
                item.updatedAt() != null ? item.updatedAt().toString() : null
        );
    }

    public static List<AdminProductListItemDto> fromList(List<AdminProductListItem> items) {
        return items.stream().map(AdminProductListItemDto::from).toList();
    }
}
