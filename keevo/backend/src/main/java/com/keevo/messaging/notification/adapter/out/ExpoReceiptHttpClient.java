package com.keevo.messaging.notification.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.messaging.notification.domain.port.out.ExpoReceiptClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.9b — {@code POST https://exp.host/--/api/v2/push/getReceipts {ids}} with the Expo client (connect 5 s,
 * read 10 s) and the optional {@code EXPO_ACCESS_TOKEN}. Answer {@code {data: {id: {status, details: {error}}}}}.
 */
@Component
@ConditionalOnProperty(name = "keevo.push.expo.enabled", havingValue = "true")
public class ExpoReceiptHttpClient implements ExpoReceiptClient {

    static final String RECEIPTS_URL = "https://exp.host/--/api/v2/push/getReceipts";

    private final RestTemplate http;
    private final String accessToken;

    public ExpoReceiptHttpClient(@Qualifier("expoRestTemplate") RestTemplate http,
                                 @Value("${keevo.push.expo.access-token:}") String accessToken) {
        this.http = http;
        this.accessToken = accessToken == null ? "" : accessToken.strip();
    }

    @Override
    public Map<String, String> fetchReceipts(Collection<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) return Map.of();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!accessToken.isEmpty()) headers.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> response = http.exchange(RECEIPTS_URL, HttpMethod.POST,
                new HttpEntity<>(Map.of("ids", List.copyOf(ticketIds)), headers), JsonNode.class);
        JsonNode data = response.getBody() == null ? null : response.getBody().path("data");
        if (data == null || !data.isObject()) {
            throw new IllegalStateException("Expo receipts answer without data");
        }
        Map<String, String> receipts = new HashMap<>();
        data.fields().forEachRemaining(entry -> {
            JsonNode receipt = entry.getValue();
            receipts.put(entry.getKey(), "ok".equals(receipt.path("status").asText())
                    ? "" : receipt.path("details").path("error").asText("unknown"));
        });
        return receipts;
    }
}
