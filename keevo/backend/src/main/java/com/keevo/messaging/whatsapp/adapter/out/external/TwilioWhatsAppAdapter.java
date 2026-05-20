package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
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
 * Twilio WhatsApp adapter — sends messages via the Twilio Messaging API.
 *
 * <p>Active when {@code keevo.whatsapp.provider=twilio}.
 * Uses HTTP Basic Auth (AccountSid:AuthToken) and application/x-www-form-urlencoded body.
 * The auth token is NEVER logged in full — only the first 4 characters.
 */
@Component
@ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "twilio")
public class TwilioWhatsAppAdapter implements WhatsAppPort {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppAdapter.class);
    private static final String TWILIO_MESSAGES_URL =
            "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final RestTemplate restTemplate;
    private final TwilioProperties properties;

    public TwilioWhatsAppAdapter(@Qualifier("twilioRestTemplate") RestTemplate restTemplate,
                                 TwilioProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;

        if (!properties.isConfigured()) {
            log.error("[WhatsApp] Twilio provider selected but credentials are missing — check TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_FROM_NUMBER");
        } else {
            String tokenPrefix = properties.authToken().length() >= 4
                    ? properties.authToken().substring(0, 4)
                    : "????";
            log.info("[WhatsApp] Using Twilio — from: {}, auth token: {}****", properties.fromNumber(), tokenPrefix);
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public void sendReport(String phoneNumber, String reportText) {
        String sanitizedPhone = sanitizePhone(phoneNumber);
        try {
            doSend(sanitizedPhone, reportText);
        } catch (Exception e) {
            throw new WhatsAppDeliveryException("Twilio delivery failed: " + e.getMessage(), e);
        }
    }

    void doSend(String sanitizedPhone, String reportText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", basicAuthHeader());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", "whatsapp:" + properties.fromNumber());
        form.add("To", "whatsapp:" + sanitizedPhone);
        form.add("Body", reportText);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        restTemplate.exchange(
                String.format(TWILIO_MESSAGES_URL, properties.accountSid()),
                HttpMethod.POST,
                entity,
                String.class);
    }

    private String basicAuthHeader() {
        String credentials = properties.accountSid() + ":" + properties.authToken();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizePhone(String phone) {
        String cleaned = phone.replaceAll("[\\s\\-()]", "");
        if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }
}
