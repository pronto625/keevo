package com.keevo.inventory.counting.adapter.in.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkSaveCountsRequestDto(
        @NotEmpty @Valid List<SaveInventoryCountRequestDto> counts
) {}
