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
 * Story 19.9 — Resend: {@code POST https://api.resend.com/emails}, header {@code Authorization: Bearer <key>}, body
 * {@code {from, to:[to], subject, text, html?, attachments?:[{filename, content(base64)}]}} (Bref
 * {@code email.service.ts}). Delivered on HTTP 2xx.
 */
@Component
public class ResendEmailProvider implements EmailProvider {

    public static final String NAME = "resend";
    static final String URL = "https://api.resend.com/emails";

    private final RestTemplate http;
    private final EmailChannelProperties properties;

    public ResendEmailProvider(@Qualifier("channelRestTemplate") RestTemplate http,
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
        return !properties.resendApiKey().isEmpty();
    }

    @Override
    public SendOutcome send(EmailMessage message, String sender) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.resendApiKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", sender);
        body.put("to", List.of(message.to()));
        body.put("subject", nullToEmpty(message.subject()));
        body.put("text", nullToEmpty(message.text()));
        if (message.html() != null && !message.html().isBlank()) {
            body.put("html", message.html());
        }
        if (!message.attachments().isEmpty()) {
            body.put("attachments", message.attachments().stream().map(ResendEmailProvider::attachment).toList());
        }
        return ProviderHttp.postJson(http, "Email", NAME, URL, headers, body).outcome();
    }

    private static Map<String, Object> attachment(EmailAttachment a) {
        return Map.of("filename", nullToEmpty(a.filename()),
                "content", Base64.getEncoder().encodeToString(a.content() == null ? new byte[0] : a.content()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
