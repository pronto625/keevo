package com.keevo.admin.catalog.adapter.in.rest.dto;

import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import org.springframework.data.domain.Page;

import java.util.List;

public record AdminProductPageResponse(
        List<AdminProductListItemDto> items,
        long totalCount,
        int page,
        int pageSize
) {
    public static AdminProductPageResponse from(Page<AdminProductListItem> pageResult) {
        return new AdminProductPageResponse(
                AdminProductListItemDto.fromList(pageResult.getContent()),
                pageResult.getTotalElements(),
                pageResult.getNumber(),
                pageResult.getSize()
        );
    }
}
