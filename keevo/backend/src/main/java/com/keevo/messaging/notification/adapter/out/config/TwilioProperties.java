package com.keevo.messaging.notification.adapter.out.config;

public record TwilioProperties(
        String accountSid,
        String authToken,
        String fromNumber
) {
    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank()
                && fromNumber != null && !fromNumber.isBlank();
    }
}
