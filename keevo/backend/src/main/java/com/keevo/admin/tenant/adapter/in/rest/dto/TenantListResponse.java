package com.keevo.admin.tenant.adapter.in.rest.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * TenantListResponse — paginated response envelope for the admin tenant list.
 */
public record TenantListResponse(
        List<AdminTenantListItemDto> items,
        long totalCount,
        int page,
        int pageSize
) {
    public static TenantListResponse from(Page<com.keevo.admin.tenant.domain.model.AdminTenantListItem> page) {
        List<AdminTenantListItemDto> items = page.getContent().stream()
                .map(AdminTenantListItemDto::from)
                .toList();
        return new TenantListResponse(items, page.getTotalElements(),
                page.getNumber(), page.getSize());
    }
}
