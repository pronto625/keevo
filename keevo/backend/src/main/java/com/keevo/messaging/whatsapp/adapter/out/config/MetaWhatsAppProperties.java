package com.keevo.messaging.whatsapp.adapter.out.config;

/**
 * Story 19.9b — {@code keevo.whatsapp.meta.*}: Meta WhatsApp Cloud API keys of the platform number (Bref's
 * {@code WHATSAPP_ACCESS_TOKEN}, {@code WHATSAPP_PHONE_NUMBER_ID}) and the sign-in code template
 * ({@code WHATSAPP_OTP_TEMPLATE}, default {@code bref_point_otp}).
 */
public record MetaWhatsAppProperties(String accessToken, String phoneNumberId, String otpTemplate) {

    public static final String DEFAULT_OTP_TEMPLATE = "bref_point_otp";

    public MetaWhatsAppProperties {
        accessToken = accessToken == null ? "" : accessToken.strip();
        phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.strip();
        otpTemplate = otpTemplate == null || otpTemplate.isBlank() ? DEFAULT_OTP_TEMPLATE : otpTemplate.strip();
    }

    public boolean isConfigured() {
        return !accessToken.isEmpty() && !phoneNumberId.isEmpty();
    }
}
