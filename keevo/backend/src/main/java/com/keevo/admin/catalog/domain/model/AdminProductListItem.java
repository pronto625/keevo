package com.keevo.admin.catalog.domain.model;

import java.time.Instant;

public record AdminProductListItem(
        String id,
        String name,
        String tenantId,
        String tenantName,
        String tenantPlan,       // plan class by kind: FREE | PAID (trial or paid plan)
        String schemaName,       // internal use — not serialized to DTO
        String categoryName,     // nullable
        long price,              // in XAF (integer)
        int stockQuantity,
        String status,           // ACTIVE | DRAFT | ARCHIVED
        Instant updatedAt
) {}
