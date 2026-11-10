package com.keevo.whatsappcloud.config;

/**
 * Story 21.5 — the Meta app settings of the webhook: {@code appSecret} (signs every delivery,
 * {@code X-Hub-Signature-256}) and {@code verifyToken} (the subscription check of {@code GET}). Blank = refused: no
 * delivery is accepted without a secret, no subscription without a token. Never logged.
 */
public record WhatsAppCloudSettings(String appSecret, String verifyToken) {

    public WhatsAppCloudSettings {
        appSecret = appSecret == null ? "" : appSecret.strip();
        verifyToken = verifyToken == null ? "" : verifyToken.strip();
    }

    @Override
    public String toString() {
        return "WhatsAppCloudSettings[appSecret=" + (appSecret.isEmpty() ? "unset" : "set")
                + ", verifyToken=" + (verifyToken.isEmpty() ? "unset" : "set") + "]";
    }
}
