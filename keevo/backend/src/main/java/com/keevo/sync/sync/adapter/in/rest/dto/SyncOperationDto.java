package com.keevo.sync.sync.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public record SyncOperationDto(
        @NotBlank String operationId,
        @NotBlank String operationType,
        String entityId,
        @NotNull Map<String, Object> payload,
        @NotNull Instant clientTimestamp
) {}
