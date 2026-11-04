package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.channel.adapter.out.external.ProviderHttp;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Wassender WhatsApp provider — free text via {@code POST {api-url}/api/send-message}, {@code Authorization: Bearer}.
 * Story 8.0 — AC5. Story 19.9b — a {@link WhatsAppProvider} of the chain (same HTTP call): any failure, a timeout
 * included, is {@link SendOutcome#FAILED} so the chain falls back to Twilio as the former failover adapter did.
 *
 * <p>The API token, the text and the full phone are never logged.
 */
@Component
public class WassenderWhatsAppAdapter implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(WassenderWhatsAppAdapter.class);

    public static final String NAME = "wassender";

    private final RestTemplate restTemplate;
    private final WassenderProperties properties;

    public WassenderWhatsAppAdapter(@Qualifier("wassenderRestTemplate") RestTemplate restTemplate,
                                    WassenderProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** True only when the Wassender API token is set (v1s-15-9). */
    @Override
    public boolean isConfigured() {
        return properties.apiToken() != null && !properties.apiToken().isBlank();
    }

    @Override
    public SendOutcome sendText(String phone, String text) {
        return post(phone, text, false);
    }

    /** Story 19.9b — the fallback of a code: a read timeout is {@link SendOutcome#UNKNOWN} (no second code). */
    @Override
    public SendOutcome sendCodeText(String phone, String text) {
        return post(phone, text, true);
    }

    private SendOutcome post(String phone, String text, boolean code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + properties.apiToken());

        String body = String.format("{\"to\":\"%s\",\"text\":\"%s\"}", escapeJson(phone),
                escapeJson(text == null ? "" : text));
        try {
            restTemplate.exchange(properties.apiUrl() + "/api/send-message", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            return SendOutcome.DELIVERED;
        } catch (RestClientResponseException e) {
            log.warn("[WhatsApp] wassender failed (HTTP {})", e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            if (code && ProviderHttp.isReadTimeout(e)) {
                log.warn("[WhatsApp] wassender did not answer in time — code delivery unknown");
                return SendOutcome.UNKNOWN;
            }
            log.warn("[WhatsApp] wassender failed (connection error)");
        } catch (RuntimeException e) {
            log.warn("[WhatsApp] wassender failed ({})", e.getClass().getSimpleName());
        }
        return SendOutcome.FAILED;
    }

    static String escapeJson(String text) {
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
