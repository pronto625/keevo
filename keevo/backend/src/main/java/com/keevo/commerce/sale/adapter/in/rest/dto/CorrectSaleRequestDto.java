package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.util.Map;
import java.util.UUID;

public record CorrectSaleRequestDto(
        String justification,
        Map<UUID, Integer> itemQuantities
) {}
