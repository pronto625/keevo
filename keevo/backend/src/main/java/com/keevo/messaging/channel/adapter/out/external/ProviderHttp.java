package com.keevo.messaging.channel.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;

/**
 * Story 19.9 — one JSON POST to a provider, shared by the SMS / email adapters. A failure is logged at WARN with the
 * channel, the provider name and the HTTP status only: never the URL query, the key, the body or the response text.
 *
 * <p>A 2xx answer is accepted whatever its body (read as text, parsed leniently: an unparsable body is still an
 * acceptance — failing over would send the message twice). A read timeout is {@link SendOutcome#UNKNOWN}: the request
 * went out and the provider may have accepted it. Connect failures and non-2xx are {@link SendOutcome#FAILED}.
 */
public final class ProviderHttp {

    private static final Logger log = LoggerFactory.getLogger(ProviderHttp.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProviderHttp() {
    }

    /** Outcome of the call; {@code body} is the parsed 2xx body, null when absent or not JSON. */
    record Response(SendOutcome outcome, JsonNode body) {
    }

    static Response postJson(RestTemplate http, String channel, String provider, String url, HttpHeaders headers,
                             Object body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("[{}] {} failed (HTTP {})", channel, provider, response.getStatusCode().value());
                return new Response(SendOutcome.FAILED, null);
            }
            return new Response(SendOutcome.DELIVERED, parse(response.getBody()));
        } catch (RestClientResponseException e) {
            log.warn("[{}] {} failed (HTTP {})", channel, provider, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            if (isReadTimeout(e)) {
                log.warn("[{}] {} did not answer in time — delivery unknown, no failover", channel, provider);
                return new Response(SendOutcome.UNKNOWN, null);
            }
            log.warn("[{}] {} failed (connection error)", channel, provider);
        } catch (RuntimeException e) {
            log.warn("[{}] {} failed ({})", channel, provider, e.getClass().getSimpleName());
        }
        return new Response(SendOutcome.FAILED, null);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A socket timeout once connected (a connect timeout says so in its message and is a plain failure). Story 19.9b
     * — public for the WhatsApp Meta provider, which reads the error body itself.
     */
    public static boolean isReadTimeout(ResourceAccessException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                return !message.contains("connect");
            }
        }
        return false;
    }
}
