package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.util.Map;
import java.util.UUID;

public record ValidateSaleRequestDto(
        String justification,
        Map<UUID, UUID> productIdRemappings,
        Map<UUID, Integer> initialStockEntries
) {}
