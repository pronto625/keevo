package com.keevo.messaging.notification.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteDeviceTokenRequestDto(
        @NotBlank(message = "FCM token must not be blank")
        String token
) {}
