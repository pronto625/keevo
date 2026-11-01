package com.keevo.messaging.notification.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteDeviceTokenRequestDto(
        @NotBlank(message = "Le jeton de notification est obligatoire")
        String token
) {}
