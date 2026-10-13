package com.keevo.admin.operations.adapter.in.rest.dto;

import com.keevo.admin.operations.domain.model.TenantActivityPage;

import java.util.List;

/** Contract with keevo/dashboard/src/types/operations.ts (AdminTenantActivityPage). */
public record TenantActivityPageDto(
        List<TenantActivityDto> content,
        long totalElements,
        int totalPages,
        int number
) {
    public static TenantActivityPageDto from(TenantActivityPage m) {
        return new TenantActivityPageDto(
                m.content().stream().map(TenantActivityDto::from).toList(),
                m.totalElements(), m.totalPages(), m.number());
    }
}
