package com.keevo.catalog.category.adapter.in.web.dto;

import com.keevo.catalog.category.domain.model.Category;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryResponseDto — Response DTO for category data.
 */
public record CategoryResponseDto(
    UUID id,
    String name,
    UUID parentId,
    boolean isActive,
    boolean isCustom,
    Instant createdAt,
    Instant updatedAt
) {
    
    public static CategoryResponseDto fromDomain(Category category) {
        return new CategoryResponseDto(
            category.id(),
            category.name(),
            category.parentId(),
            category.isActive(),
            category.isCustom(),
            category.createdAt(),
            category.updatedAt()
        );
    }
}