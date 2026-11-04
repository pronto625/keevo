package com.keevo.messaging.channel.adapter.out.external;

import com.keevo.messaging.channel.adapter.out.config.SmsChannelProperties;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Story 19.9 — MboaSMS: {@code POST {base}/api/v1/developer/sms/send}, header {@code X-API-Key}, body
 * {@code {phoneNumbers:[to], message, senderId}} (Bref {@code sms.service.ts}). Delivered on HTTP 2xx.
 */
@Component
public class MboaSmsProvider implements SmsProvider {

    public static final String NAME = "mboasms";
    static final String PATH = "/api/v1/developer/sms/send";

    private final RestTemplate http;
    private final SmsChannelProperties properties;

    public MboaSmsProvider(@Qualifier("channelRestTemplate") RestTemplate http, SmsChannelProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return properties.mboasms().isConfigured();
    }

    @Override
    public SendOutcome send(String phone, String text, String senderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", properties.mboasms().apiKey());
        Map<String, Object> body = Map.of(
                "phoneNumbers", List.of(phone),
                "message", text == null ? "" : text,
                "senderId", senderId == null ? "" : senderId);
        return ProviderHttp.postJson(http, "SMS", NAME, properties.mboasms().url(PATH), headers, body).outcome();
    }
}
