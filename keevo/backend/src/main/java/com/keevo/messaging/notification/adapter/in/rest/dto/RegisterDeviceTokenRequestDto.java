package com.keevo.messaging.notification.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequestDto(
        @NotBlank(message = "Le jeton de notification est obligatoire")
        String token,

        @NotBlank(message = "La plateforme de l'appareil est obligatoire")
        String platform,

        String deviceName
) {}
