package com.keevo.catalog.category.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Category — Domain model for a product category within a tenant.
 *
 * <p>Immutable value object — state is managed at persistence layer.
 *
 * <p>AC10: parent_id = NULL → root category; non-null → subcategory.
 * <p>AC11: is_active → deactivatable/reactivatable, never deleted.
 * <p>AC12: is_custom → false = seeded template; true = merchant-created.
 *
 * <p>Pure Java — NO Spring/framework imports.
 */
public record Category(
        UUID id,
        String name,
        UUID parentId,      // null = root category; non-null = subcategory
        boolean isActive,   // deactivatable/reactivatable without deletion
        boolean isCustom,   // false = seeded template; true = merchant-created
        Instant createdAt,
        Instant updatedAt
) {}