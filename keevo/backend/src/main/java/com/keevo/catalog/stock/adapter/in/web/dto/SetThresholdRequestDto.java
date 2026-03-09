package com.keevo.catalog.stock.adapter.in.web.dto;

import jakarta.validation.constraints.Min;

/**
 * SetThresholdRequestDto — body for PATCH /{productId}/threshold.
 * Story 2.3.
 */
public record SetThresholdRequestDto(
    @Min(0) int minimumThreshold
) {}
