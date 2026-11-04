package com.keevo.messaging.channel.adapter.out.external;

import com.keevo.messaging.channel.adapter.out.config.EmailChannelProperties;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.EmailProvider;
import com.keevo.shared.capability.notification.EmailAttachment;
import com.keevo.shared.capability.notification.EmailMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.9 — Infobip email: {@code POST {base}/email/4/messages}, header {@code Authorization: App <key>}, body
 * {@code {messages:[{sender, destinations:[{to:[{destination}]}], content:{subject, text, html?}, attachments?}]}}
 * (Bref {@code email.service.ts}). Delivered on HTTP 2xx.
 */
@Component
public class InfobipEmailProvider implements EmailProvider {

    public static final String NAME = "infobip";
    static final String PATH = "/email/4/messages";

    private final RestTemplate http;
    private final EmailChannelProperties properties;

    public InfobipEmailProvider(@Qualifier("channelRestTemplate") RestTemplate http,
                                EmailChannelProperties properties) {
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
    public SendOutcome send(EmailMessage message, String sender) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "App " + properties.infobip().apiKey());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subject", nullToEmpty(message.subject()));
        content.put("text", nullToEmpty(message.text()));
        if (message.html() != null && !message.html().isBlank()) {
            content.put("html", message.html());
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sender", sender);
        entry.put("destinations", List.of(Map.of("to", List.of(Map.of("destination", message.to())))));
        entry.put("content", content);
        if (!message.attachments().isEmpty()) {
            entry.put("attachments", message.attachments().stream().map(InfobipEmailProvider::attachment).toList());
        }
        return ProviderHttp.postJson(http, "Email", NAME, properties.infobip().url(PATH), headers,
                Map.of("messages", List.of(entry))).outcome();
    }

    private static Map<String, Object> attachment(EmailAttachment a) {
        return Map.of(
                "data", Base64.getEncoder().encodeToString(a.content() == null ? new byte[0] : a.content()),
                "fileName", nullToEmpty(a.filename()),
                "contentType", a.contentType() == null || a.contentType().isBlank()
                        ? "application/octet-stream" : a.contentType());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
