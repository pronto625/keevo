package com.keevo.sync.sync.adapter.in.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SyncPushRequestDto(
        @NotBlank String deviceId,
        @NotEmpty @Valid List<SyncOperationDto> operations
) {}
