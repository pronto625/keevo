package com.keevo.admin.catalog.domain.port.in;

public record ListProductsQuery(
        String search,          // nullable — product name ILIKE
        String tenantId,        // nullable UUID string — filter by tenant
        String stockLevel,      // nullable: ALL | OK | LOW | OUT
        String status,          // nullable: ALL | ACTIVE | DRAFT | ARCHIVED
        String plan,            // nullable: ALL | FREE | PAID
        int page,
        int pageSize
) {}
