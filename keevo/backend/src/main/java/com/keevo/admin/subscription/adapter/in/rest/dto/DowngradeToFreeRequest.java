package com.keevo.admin.subscription.adapter.in.rest.dto;

import jakarta.validation.constraints.Size;

/** DowngradeToFreeRequest — body of {@code POST …/plan/free} (Story 9-2); {@code reason} optional. */
public record DowngradeToFreeRequest(
        @Size(max = 500, message = "500 caractères maximum")
        String reason
) {}
