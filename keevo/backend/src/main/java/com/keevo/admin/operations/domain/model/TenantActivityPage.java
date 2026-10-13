package com.keevo.admin.operations.domain.model;

import java.util.List;

/** One page of {@link TenantActivity}, sorted by GMV desc then name (Story 9.7). */
public record TenantActivityPage(
        List<TenantActivity> content,
        long totalElements,
        int totalPages,
        int number
) {}
