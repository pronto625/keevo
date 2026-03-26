package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryScope;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateInventorySessionRequestDto(
        @NotNull UUID storeId,
        @NotNull InventoryScope scope,
        List<UUID> categoryIds
) {}
