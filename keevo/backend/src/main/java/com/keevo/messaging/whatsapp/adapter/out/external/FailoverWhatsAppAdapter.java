package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
import com.keevo.messaging.notification.adapter.out.config.WassenderProperties;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Failover WhatsApp adapter — tries Wassender first; falls back to Twilio on any error.
 *
 * <p>Active when {@code keevo.whatsapp.provider=failover}.
 * Both providers must have their credentials configured in the environment.
 * The adapter reports itself as configured as long as at least one provider has credentials.
 */
@Component
@ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "failover")
public class FailoverWhatsAppAdapter implements WhatsAppPort {

    private static final Logger log = LoggerFactory.getLogger(FailoverWhatsAppAdapter.class);
    private static final String TWILIO_MESSAGES_URL =
            "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final RestTemplate wassenderRestTemplate;
    private final RestTemplate twilioRestTemplate;
    private final WassenderProperties wassenderProperties;
    private final TwilioProperties twilioProperties;

    public FailoverWhatsAppAdapter(
            @Qualifier("wassenderRestTemplate") RestTemplate wassenderRestTemplate,
            @Qualifier("twilioRestTemplate") RestTemplate twilioRestTemplate,
            WassenderProperties wassenderProperties,
            TwilioProperties twilioProperties) {
        this.wassenderRestTemplate = wassenderRestTemplate;
        this.twilioRestTemplate = twilioRestTemplate;
        this.wassenderProperties = wassenderProperties;
        this.twilioProperties = twilioProperties;

        boolean wassenderReady = wassenderProperties.apiToken() != null && !wassenderProperties.apiToken().isBlank();
        boolean twilioReady = twilioProperties.isConfigured();

        log.info("[WhatsApp] Failover mode — Wassender: {}, Twilio: {}",
                wassenderReady ? "configured" : "MISSING",
                twilioReady ? "configured" : "MISSING");
    }

    @Override
    public boolean isConfigured() {
        boolean wassenderReady = wassenderProperties.apiToken() != null && !wassenderProperties.apiToken().isBlank();
        return wassenderReady || twilioProperties.isConfigured();
    }

    @Override
    public void sendReport(String phoneNumber, String reportText) {
        String sanitizedPhone = sanitizePhone(phoneNumber);

        if (tryWassender(sanitizedPhone, reportText)) {
            return;
        }

        log.warn("[WhatsApp] Wassender failed — switching to Twilio fallback");

        if (tryTwilio(sanitizedPhone, reportText)) {
            return;
        }

        throw new WhatsAppDeliveryException(
                "Both Wassender and Twilio failed to deliver the message to " + sanitizedPhone);
    }

    private boolean tryWassender(String sanitizedPhone, String reportText) {
        if (wassenderProperties.apiToken() == null || wassenderProperties.apiToken().isBlank()) {
            log.warn("[WhatsApp] Wassender skipped — WASSENDER_API_TOKEN not configured");
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + wassenderProperties.apiToken());

            String body = String.format(
                    "{\"to\":\"%s\",\"text\":\"%s\"}",
                    sanitizedPhone,
                    escapeJson(reportText));

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            wassenderRestTemplate.exchange(
                    wassenderProperties.apiUrl() + "/api/send-message",
                    HttpMethod.POST,
                    entity,
                    String.class);

            log.debug("[WhatsApp] Wassender delivery succeeded for {}", sanitizedPhone);
            return true;
        } catch (Exception e) {
            log.warn("[WhatsApp] Wassender delivery failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean tryTwilio(String sanitizedPhone, String reportText) {
        if (!twilioProperties.isConfigured()) {
            log.warn("[WhatsApp] Twilio skipped — credentials not configured");
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", twilioBasicAuthHeader());

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("From", "whatsapp:" + twilioProperties.fromNumber());
            form.add("To", "whatsapp:" + sanitizedPhone);
            form.add("Body", reportText);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
            twilioRestTemplate.exchange(
                    String.format(TWILIO_MESSAGES_URL, twilioProperties.accountSid()),
                    HttpMethod.POST,
                    entity,
                    String.class);

            log.debug("[WhatsApp] Twilio delivery succeeded for {}", sanitizedPhone);
            return true;
        } catch (Exception e) {
            log.warn("[WhatsApp] Twilio delivery failed: {}", e.getMessage());
            return false;
        }
    }

    private String twilioBasicAuthHeader() {
        String credentials = twilioProperties.accountSid() + ":" + twilioProperties.authToken();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
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
