package com.keevo.messaging.notification.adapter.out.config;

public record FcmProperties(
        boolean enabled,
        String credentialsPath
) {}
