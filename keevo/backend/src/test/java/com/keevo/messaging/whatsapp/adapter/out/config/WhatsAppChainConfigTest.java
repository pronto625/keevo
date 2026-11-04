package com.keevo.messaging.whatsapp.adapter.out.config;

import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 19.9b — {@code keevo.whatsapp.provider} presets keep their meaning and gain {@code meta}. */
@DisplayName("WhatsApp presets (Story 19.9b)")
class WhatsAppChainConfigTest {

    @Test
    @DisplayName("each preset gives its ordered providers; blank = noop; case and spaces ignored")
    void presets() {
        assertThat(WhatsAppChainConfig.presetProviders("noop")).containsExactly("noop");
        assertThat(WhatsAppChainConfig.presetProviders(null)).containsExactly("noop");
        assertThat(WhatsAppChainConfig.presetProviders("  ")).containsExactly("noop");
        assertThat(WhatsAppChainConfig.presetProviders("wassender")).containsExactly("wassender");
        assertThat(WhatsAppChainConfig.presetProviders("twilio")).containsExactly("twilio");
        assertThat(WhatsAppChainConfig.presetProviders("Failover")).containsExactly("wassender", "twilio");
        assertThat(WhatsAppChainConfig.presetProviders(" META ")).containsExactly("meta", "wassender", "twilio");
    }

    @Test
    @DisplayName("an unknown preset fails naming the accepted values")
    void unknown() {
        assertThatThrownBy(() -> WhatsAppChainConfig.presetProviders("infobip"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("meta");
    }

    @Test
    @DisplayName("meta properties: Bref's default template, keys stripped")
    void metaProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("keevo.whatsapp.meta.access-token", " tok ")
                .withProperty("keevo.whatsapp.meta.phone-number-id", "123");
        MetaWhatsAppProperties props = new WhatsAppChainConfig().metaWhatsAppProperties(env);
        assertThat(props.accessToken()).isEqualTo("tok");
        assertThat(props.otpTemplate()).isEqualTo("bref_point_otp");
        assertThat(props.isConfigured()).isTrue();
        assertThat(new MetaWhatsAppProperties("", "123", null).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("the sender bean is built from the preset over the available providers")
    void senderBean() {
        WhatsAppProvider wassender = provider("wassender");
        WhatsAppProvider twilio = provider("twilio");
        MockEnvironment env = new MockEnvironment().withProperty("keevo.whatsapp.provider", "failover");
        assertThat(new WhatsAppChainConfig().whatsAppSender(env, List.of(twilio, wassender)).isConfigured()).isTrue();
        MockEnvironment meta = new MockEnvironment().withProperty("keevo.whatsapp.provider", "meta");
        assertThatThrownBy(() -> new WhatsAppChainConfig().whatsAppSender(meta, List.of(twilio, wassender)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("meta");
    }

    private static WhatsAppProvider provider(String name) {
        return new WhatsAppProvider() {
            @Override public String name() { return name; }
            @Override public boolean isConfigured() { return true; }
            @Override public SendOutcome sendText(String phone, String text) { return SendOutcome.DELIVERED; }
        };
    }
}
