package com.keevo.messaging.channel.adapter.out.config;

/**
 * Story 19.9 — base URL and API key of one provider. A base URL without scheme gets {@code https://} (Infobip gives
 * {@code xxxx.api.infobip.com}).
 */
public record ProviderKeys(String baseUrl, String apiKey) {

    public ProviderKeys {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    public boolean isConfigured() {
        return hasKey() && !baseUrl.isEmpty();
    }

    /** {@code baseUrl + path}, with a scheme and without a doubled slash. */
    public String url(String path) {
        String base = baseUrl.contains("://") ? baseUrl : "https://" + baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
