package com.keevo.whatsappcloud.config;

/**
 * Story 21.6 — the platform WhatsApp number (Bref « Bref Point », the 19.9b sign-in number) as the WhatsApp Business
 * module uses it: its phone number id (Meta's webhook routes its messages to the platform inbox), its WABA (template
 * listing) and its access token ({@code keevo.whatsapp-cloud.platform.*}, defaulting to the 19.9b
 * {@code WHATSAPP_PHONE_NUMBER_ID} / {@code WHATSAPP_ACCESS_TOKEN}). Never logged.
 */
public record PlatformNumberSettings(String phoneNumberId, String wabaId, String token) {

    public PlatformNumberSettings {
        phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.strip();
        wabaId = wabaId == null ? "" : wabaId.strip();
        token = token == null ? "" : token.strip();
    }

    /** Id and token present: messages can be sent from the number (when the Graph API is enabled). */
    public boolean isConfigured() {
        return !phoneNumberId.isEmpty() && !token.isEmpty();
    }

    /** Whether {@code phoneNumberId} is the platform number (an unconfigured platform matches nothing). */
    public boolean isPlatformNumber(String id) {
        return !phoneNumberId.isEmpty() && phoneNumberId.equals(id);
    }

    @Override
    public String toString() {
        return "PlatformNumberSettings[phoneNumberId=" + (phoneNumberId.isEmpty() ? "unset" : "set")
                + ", wabaId=" + (wabaId.isEmpty() ? "unset" : "set") + ", token=" + (token.isEmpty() ? "unset" : "set")
                + "]";
    }
}
