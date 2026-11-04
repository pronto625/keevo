package com.keevo.messaging.notification.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.messaging.notification.domain.model.PushTokens;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.PushDeliveryChannel;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.9 — Expo push delivery (the provider of the React Native app, which prevails): {@code POST
 * https://exp.host/--/api/v2/push/send}, at most {@value #CHUNK_SIZE} messages per request, {@code sound:'default'},
 * {@code priority:'high'}, {@code channelId:'default'}, {@code data} = metadata + {@code deepLink} + {@code type};
 * {@code Authorization: Bearer} when {@code keevo.push.expo.access-token} is set. Supports the Expo tokens
 * ({@link PushTokens#isExpo}); the tokens whose ticket says {@code DeviceNotRegistered} are deleted in one statement.
 * Receipts are not polled (ticket errors only, as Bref). Best effort: never throws.
 *
 * <p>Cost per event: ⌈tokens/100⌉ requests + at most 1 DELETE.
 */
@Component
@ConditionalOnProperty(name = "keevo.push.expo.enabled", havingValue = "true")
public class ExpoPushAdapter implements PushDeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushAdapter.class);

    static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    static final int CHUNK_SIZE = 100;

    private final RestTemplate http;
    private final DeviceTokenRepository deviceTokens;
    private final String accessToken;

    public ExpoPushAdapter(@Qualifier("expoRestTemplate") RestTemplate http, DeviceTokenRepository deviceTokens,
                           @Value("${keevo.push.expo.access-token:}") String accessToken) {
        this.http = http;
        this.deviceTokens = deviceTokens;
        this.accessToken = accessToken == null ? "" : accessToken.strip();
    }

    @Override
    public boolean supports(String token) {
        return PushTokens.isExpo(token);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public PushSendResult send(String tenantSchema, List<String> tokens, NotificationPayload payload) {
        if (tokens == null || tokens.isEmpty()) return PushSendResult.EMPTY;
        Map<String, String> data = data(payload);
        List<String> unregistered = new ArrayList<>();
        PushSendResult total = PushSendResult.EMPTY;
        for (int from = 0; from < tokens.size(); from += CHUNK_SIZE) {
            List<String> chunk = tokens.subList(from, Math.min(from + CHUNK_SIZE, tokens.size()));
            total = total.plus(sendChunk(tenantSchema, chunk, payload, data, unregistered));
        }
        if (!unregistered.isEmpty()) {
            TenantContext.setCurrentTenant(tenantSchema);
            try {
                int deleted = deviceTokens.deleteByTokens(unregistered);
                log.info("[Expo] removed {} unregistered token(s) for tenant={}", deleted, tenantSchema);
            } catch (RuntimeException e) {
                log.warn("[Expo] stale token cleanup failed ({})", e.getClass().getSimpleName());
            } finally {
                TenantContext.clear();
            }
        }
        return total;
    }

    private PushSendResult sendChunk(String tenantId, List<String> chunk, NotificationPayload payload,
                                     Map<String, String> data, List<String> unregistered) {
        List<Map<String, Object>> messages = new ArrayList<>(chunk.size());
        for (String token : chunk) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", token);
            m.put("title", payload.title() == null ? "" : payload.title());
            m.put("body", payload.body() == null ? "" : payload.body());
            m.put("data", data);
            m.put("sound", "default");
            m.put("priority", "high");
            m.put("channelId", "default");
            messages.add(m);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!accessToken.isEmpty()) headers.setBearerAuth(accessToken);
        JsonNode body;
        try {
            ResponseEntity<JsonNode> response = http.exchange(EXPO_PUSH_URL, HttpMethod.POST,
                    new HttpEntity<>(messages, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("[Expo] chunk of {} failed for tenant={} (HTTP {})", chunk.size(), tenantId,
                        response.getStatusCode().value());
                return new PushSendResult(0, chunk.size(), 1, 1);
            }
            body = response.getBody();
        } catch (RestClientResponseException e) {
            log.warn("[Expo] chunk of {} failed for tenant={} (HTTP {})", chunk.size(), tenantId,
                    e.getStatusCode().value());
            return new PushSendResult(0, chunk.size(), 1, 1);
        } catch (RuntimeException e) {
            log.warn("[Expo] chunk of {} failed for tenant={} ({})", chunk.size(), tenantId,
                    e.getClass().getSimpleName());
            return new PushSendResult(0, chunk.size(), 1, 1);
        }
        JsonNode tickets = body == null ? null : body.path("data");
        if (tickets == null || !tickets.isArray()) {
            // request-level error (top-level "errors", no tickets): nothing was accepted
            log.warn("[Expo] chunk of {} refused for tenant={} ({})", chunk.size(), tenantId,
                    body == null ? "empty answer" : body.path("errors").path(0).path("code").asText("no tickets"));
            return new PushSendResult(0, chunk.size(), 1, 1);
        }
        return tickets(tickets, chunk, unregistered);
    }

    /** Tickets come back in the order of the messages. */
    private static PushSendResult tickets(JsonNode tickets, List<String> chunk, List<String> unregistered) {
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < chunk.size(); i++) {
            JsonNode ticket = tickets.path(i);
            if ("ok".equals(ticket.path("status").asText())) {
                ok++;
                continue;
            }
            failed++;
            String error = ticket.path("details").path("error").asText("");
            if ("DeviceNotRegistered".equals(error)) {
                unregistered.add(chunk.get(i));
            } else {
                log.warn("[Expo] ticket error {}", error.isEmpty() ? "unknown" : error);
            }
        }
        return new PushSendResult(ok, failed, 1, 0);
    }

    private static Map<String, String> data(NotificationPayload payload) {
        Map<String, String> data = new HashMap<>();
        if (payload.metadata() != null) data.putAll(payload.metadata());
        if (payload.deepLink() != null) data.put("deepLink", payload.deepLink());
        data.put("type", payload.type());
        return data;
    }
}
