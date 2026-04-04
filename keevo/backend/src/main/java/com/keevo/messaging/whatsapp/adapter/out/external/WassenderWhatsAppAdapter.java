package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Wassender WhatsApp adapter — sends messages via Wassenger API.
 * Story 8.0 — AC5.
 *
 * <p>The API token is NEVER logged in full — only the first 4 characters.
 */
@Component
@ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "wassender")
public class WassenderWhatsAppAdapter implements WhatsAppPort {

    private static final Logger log = LoggerFactory.getLogger(WassenderWhatsAppAdapter.class);

    private final RestTemplate restTemplate;
    private final WassenderProperties properties;

    public WassenderWhatsAppAdapter(@Qualifier("wassenderRestTemplate") RestTemplate restTemplate,
                                    WassenderProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;

        if (properties.apiToken() == null || properties.apiToken().isBlank()) {
            log.error("[WhatsApp] Wassender provider selected but WASSENDER_API_TOKEN is missing — falling back to NoOp");
        } else {
            String tokenPrefix = properties.apiToken().length() >= 4
                    ? properties.apiToken().substring(0, 4)
                    : "????";
            log.info("[WhatsApp] Using Wassender API token: {}****", tokenPrefix);
        }
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public void sendReport(String phoneNumber, String reportText) {
        String sanitizedPhone = sanitizePhone(phoneNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + properties.apiToken());

        String body = String.format(
                "{\"to\":\"%s\",\"text\":\"%s\"}",
                sanitizedPhone,
                escapeJson(reportText));

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.exchange(
                    properties.apiUrl() + "/api/send-message",
                    HttpMethod.POST,
                    entity,
                    String.class);
        } catch (Exception e) {
            throw new WhatsAppDeliveryException("Wassender delivery failed: " + e.getMessage(), e);
        }
    }

    private String sanitizePhone(String phone) {
        String cleaned = phone.replaceAll("[\\s\\-()]", "");
        if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private String escapeJson(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
