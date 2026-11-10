package com.keevo.whatsappcloud.webhook.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetaWebhookSignature — X-Hub-Signature-256 and verify token (21.5)")
class MetaWebhookSignatureTest {

    @Test
    @DisplayName("Meta's HMAC-SHA256 of the raw body matches; any other body, secret or header shape does not")
    void signature() {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        String header = MetaWebhookSignature.header("secret", body);
        assertThat(header).startsWith("sha256=").hasSize(71);
        assertThat(MetaWebhookSignature.isValid("secret", body, header)).isTrue();
        assertThat(MetaWebhookSignature.isValid("secret", body, header.toUpperCase().replace("SHA256=", "sha256=")))
                .isTrue();
        assertThat(MetaWebhookSignature.isValid("other", body, header)).isFalse();
        assertThat(MetaWebhookSignature.isValid("secret", "{}".getBytes(StandardCharsets.UTF_8), header)).isFalse();
        assertThat(MetaWebhookSignature.isValid("secret", body, header.substring(7))).isFalse();
        assertThat(MetaWebhookSignature.isValid("secret", body, "sha256=zz" + header.substring(9))).isFalse();
        assertThat(MetaWebhookSignature.isValid("", body, header)).isFalse();
        assertThat(MetaWebhookSignature.isValid("secret", body, null)).isFalse();
    }

    @Test
    @DisplayName("verify token: equal only, blank never matches")
    void verifyToken() {
        assertThat(MetaWebhookSignature.tokenMatches("abc", "abc")).isTrue();
        assertThat(MetaWebhookSignature.tokenMatches("abd", "abc")).isFalse();
        assertThat(MetaWebhookSignature.tokenMatches("", "")).isFalse();
        assertThat(MetaWebhookSignature.tokenMatches(null, "abc")).isFalse();
    }
}
