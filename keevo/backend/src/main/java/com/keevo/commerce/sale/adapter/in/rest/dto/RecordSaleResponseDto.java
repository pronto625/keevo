package com.keevo.commerce.sale.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

public record RecordSaleResponseDto(
        UUID id,
        String status,
        int totalAmount,
        Instant occurredAt
) {}
