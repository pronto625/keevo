package com.keevo.messaging.whatsapp.adapter.out.external;

import com.keevo.messaging.channel.adapter.out.external.ProviderHttp;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.notification.adapter.out.config.TwilioProperties;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Twilio WhatsApp provider — free text via the Twilio Messaging API (HTTP Basic {@code AccountSid:AuthToken}, form
 * body). Story 19.9b — a {@link WhatsAppProvider} of the chain (same HTTP call): any failure is
 * {@link SendOutcome#FAILED}. The auth token, the text and the full phone are never logged.
 */
@Component
public class TwilioWhatsAppAdapter implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppAdapter.class);

    public static final String NAME = "twilio";
    private static final String TWILIO_MESSAGES_URL =
            "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private final RestTemplate restTemplate;
    private final TwilioProperties properties;

    public TwilioWhatsAppAdapter(@Qualifier("twilioRestTemplate") RestTemplate restTemplate,
                                 TwilioProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
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
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", basicAuthHeader());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", "whatsapp:" + properties.fromNumber());
        form.add("To", "whatsapp:" + phone);
        form.add("Body", text == null ? "" : text);
        try {
            restTemplate.exchange(String.format(TWILIO_MESSAGES_URL, properties.accountSid()), HttpMethod.POST,
                    new HttpEntity<>(form, headers), String.class);
            return SendOutcome.DELIVERED;
        } catch (RestClientResponseException e) {
            log.warn("[WhatsApp] twilio failed (HTTP {})", e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            if (code && ProviderHttp.isReadTimeout(e)) {
                log.warn("[WhatsApp] twilio did not answer in time — code delivery unknown");
                return SendOutcome.UNKNOWN;
            }
            log.warn("[WhatsApp] twilio failed (connection error)");
        } catch (RuntimeException e) {
            log.warn("[WhatsApp] twilio failed ({})", e.getClass().getSimpleName());
        }
        return SendOutcome.FAILED;
    }

    private String basicAuthHeader() {
        String credentials = properties.accountSid() + ":" + properties.authToken();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
