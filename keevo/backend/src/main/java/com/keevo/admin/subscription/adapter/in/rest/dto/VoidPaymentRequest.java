package com.keevo.admin.subscription.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** VoidPaymentRequest — body of {@code POST …/payments/{paymentId}/void} (Story 9-2); reason 3–500 chars. */
public record VoidPaymentRequest(
        @NotBlank(message = "Motif requis")
        @Size(min = 3, max = 500, message = "Motif requis (3 à 500 caractères)")
        String reason
) {}
