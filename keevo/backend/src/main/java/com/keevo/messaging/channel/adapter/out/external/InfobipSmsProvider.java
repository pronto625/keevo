package com.keevo.messaging.channel.adapter.out.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.messaging.channel.adapter.out.config.SmsChannelProperties;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Story 19.9 — Infobip SMS: {@code POST {base}/sms/2/text/advanced}, header {@code Authorization: App <key>}, body
 * {@code {messages:[{from, destinations:[{to}], text}]}} (Bref {@code sms.service.ts}). Delivered on HTTP 2xx unless
 * a message status group of a parsable body is {@code REJECTED}.
 */
@Component
public class InfobipSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(InfobipSmsProvider.class);

    public static final String NAME = "infobip";
    static final String PATH = "/sms/2/text/advanced";

    private final RestTemplate http;
    private final SmsChannelProperties properties;

    public InfobipSmsProvider(@Qualifier("channelRestTemplate") RestTemplate http, SmsChannelProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.infobip().isConfigured();
    }

    @Override
    public SendOutcome send(String phone, String text, String senderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "App " + properties.infobip().apiKey());
        Map<String, Object> message = Map.of(
                "from", senderId == null ? "" : senderId,
                "destinations", List.of(Map.of("to", phone)),
                "text", text == null ? "" : text);
        ProviderHttp.Response response = ProviderHttp.postJson(http, "SMS", NAME, properties.infobip().url(PATH),
                headers, Map.of("messages", List.of(message)));
        if (response.outcome() != SendOutcome.DELIVERED || response.body() == null) {
            return response.outcome(); // an unparsable 2xx body stays an acceptance
        }
        for (JsonNode m : response.body().path("messages")) {
            if ("REJECTED".equalsIgnoreCase(m.path("status").path("groupName").asText(""))) {
                log.warn("[SMS] {} failed (REJECTED {})", NAME, m.path("status").path("name").asText(""));
                return SendOutcome.FAILED;
            }
        }
        return SendOutcome.DELIVERED;
    }
}
