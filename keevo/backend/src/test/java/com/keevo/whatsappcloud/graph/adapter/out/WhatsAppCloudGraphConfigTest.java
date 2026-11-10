package com.keevo.whatsappcloud.graph.adapter.out;

import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WhatsAppCloudGraphConfig / HttpMetaGraphClient — strategy validation, Meta errors, components (21.5)")
class WhatsAppCloudGraphConfigTest {

    private static final SecretCipher KEY = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    @DisplayName("disabled by default; fake only under dev/test; meta needs every key and the encryption key")
    void strategies() {
        assertThat(WhatsAppCloudGraphConfig.create(new MockEnvironment(), KEY)).isInstanceOf(DisabledMetaGraphClient.class);
        MockEnvironment prod = new MockEnvironment().withProperty("keevo.whatsapp-cloud.graph", "fake");
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> WhatsAppCloudGraphConfig.create(prod, KEY)).isInstanceOf(IllegalStateException.class);
        MockEnvironment test = new MockEnvironment().withProperty("keevo.whatsapp-cloud.graph", "fake");
        test.setActiveProfiles("test");
        assertThat(WhatsAppCloudGraphConfig.create(test, KEY)).isInstanceOf(FakeMetaGraphClient.class);
        MockEnvironment meta = new MockEnvironment().withProperty("keevo.whatsapp-cloud.graph", "meta")
                .withProperty("keevo.whatsapp-cloud.app-id", "1").withProperty("keevo.whatsapp-cloud.app-secret", "s");
        assertThatThrownBy(() -> WhatsAppCloudGraphConfig.create(meta, KEY))
                .hasMessageContaining("KEEVO_WHATSAPP_CLOUD_VERIFY_TOKEN").hasMessageNotContaining("=s");
        meta.withProperty("keevo.whatsapp-cloud.verify-token", "v");
        assertThatThrownBy(() -> WhatsAppCloudGraphConfig.create(meta, new SecretCipher("")))
                .hasMessageContaining("KEEVO_SECRETS_ENCRYPTION_KEY");
        assertThat(WhatsAppCloudGraphConfig.create(meta, KEY)).isInstanceOf(HttpMetaGraphClient.class);
        assertThatThrownBy(() -> new DisabledMetaGraphClient().sendText("1", "t", "2", "x"))
                .isInstanceOf(MetaGraphException.class);
    }

    @Test
    @DisplayName("Meta's error: user text kept, code read, no JSON → no text")
    void refusal() {
        MetaGraphException e = HttpMetaGraphClient.refusal("send", 400,
                "{\"error\":{\"message\":\"(#131026) Message undeliverable\",\"code\":131026,"
                        + "\"error_user_title\":\"Numéro injoignable\",\"error_user_msg\":\"Ce numéro n'est pas sur WhatsApp\"}}");
        assertThat(e.kind()).isEqualTo(MetaGraphException.Kind.REFUSED);
        assertThat(e.metaCode()).isEqualTo(131026);
        assertThat(e.userMessage()).isEqualTo("Numéro injoignable : Ce numéro n'est pas sur WhatsApp");
        assertThat(HttpMetaGraphClient.refusal("send", 502, "<html>").userMessage()).isNull();
    }

    @Test
    @DisplayName("template components as Bref builds them (text header, document header handle, examples, footer)")
    @SuppressWarnings("unchecked")
    void components() {
        List<Map<String, Object>> c = HttpMetaGraphClient.components(new TemplateDraft("bref_facture", "UTILITY", "fr",
                "DOCUMENT", null, "h1", "Bonjour {{1}}", List.of("Awa"), "Pied"));
        assertThat(c).hasSize(3);
        assertThat(c.get(0)).containsEntry("format", "DOCUMENT");
        assertThat((Map<String, Object>) c.get(0).get("example")).containsEntry("header_handle", List.of("h1"));
        assertThat((Map<String, Object>) c.get(1).get("example")).containsEntry("body_text", List.of(List.of("Awa")));
        assertThat(c.get(2)).containsEntry("type", "FOOTER");
        assertThat(HttpMetaGraphClient.components(new TemplateDraft("t", "UTILITY", "fr", "NONE", null, null, "Hi",
                List.of(), null))).hasSize(1);
    }
}
