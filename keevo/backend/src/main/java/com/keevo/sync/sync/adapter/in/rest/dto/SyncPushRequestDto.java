package com.keevo.sync.sync.adapter.in.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SyncPushRequestDto(
        @NotBlank String deviceId,
        @NotNull @Valid List<SyncOperationDto> operations
) {}
