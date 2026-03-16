package com.keevo.identity.employee.adapter.in.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReassignStoreRequestDto(@NotNull UUID storeId) {}
