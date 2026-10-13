package com.keevo.admin.subscription.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SuspendTenantRequest — body of {@code POST …/suspend} (Story 9-2); reason required, 3–500 chars. */
public record SuspendTenantRequest(
        @NotBlank(message = "Motif requis")
        @Size(min = 3, max = 500, message = "Motif requis (3 à 500 caractères)")
        String reason
) {}
