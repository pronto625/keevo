package com.keevo.messaging.notification.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequestDto(
        @NotBlank(message = "FCM token must not be blank")
        String token,

        @NotBlank(message = "Platform must not be blank")
        String platform,

        String deviceName
) {}
