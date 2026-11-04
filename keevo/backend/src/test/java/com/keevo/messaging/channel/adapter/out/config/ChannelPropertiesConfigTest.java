package com.keevo.messaging.channel.adapter.out.config;

import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import com.keevo.shared.capability.notification.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChannelPropertiesConfig — provider order from configuration (Story 19.9)")
class ChannelPropertiesConfigTest {

    private final ChannelPropertiesConfig config = new ChannelPropertiesConfig();

    private static SmsProvider named(String name) {
        return new SmsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public SendOutcome send(String phone, String text, String senderId) {
                return SendOutcome.DELIVERED;
            }
        };
    }

    @Test
    @DisplayName("comma list: trimmed, lower-cased, de-duplicated, order kept; blank = empty")
    void parse() {
        assertThat(ChannelPropertiesConfig.parseProviders(" MboaSMS , infobip,,mboasms ")).containsExactly("mboasms",
                "infobip");
        assertThat(ChannelPropertiesConfig.parseProviders("  ")).isEmpty();
        assertThat(ChannelPropertiesConfig.parseProviders(null)).isEmpty();
    }

    @Test
    @DisplayName("the chain follows the configured order")
    void ordered() {
        SmsProvider mboa = named("mboasms");
        SmsProvider infobip = named("infobip");
        assertThat(ChannelPropertiesConfig.ordered("keevo.sms.providers", List.of("infobip", "mboasms"),
                List.of(mboa, infobip), SmsProvider::name)).containsExactly(infobip, mboa);
    }

    @Test
    @DisplayName("an unknown provider name fails the startup")
    void unknown() {
        assertThatThrownBy(() -> ChannelPropertiesConfig.ordered("keevo.sms.providers", List.of("twilio"),
                List.of(named("mboasms")), SmsProvider::name))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keevo.sms.providers").hasMessageContaining("twilio");
    }

    @Test
    @DisplayName("empty keevo.sms.providers → SMS channel disabled; defaults empty")
    void disabledByDefault() {
        MockEnvironment env = new MockEnvironment();
        SmsChannelProperties properties = config.smsChannelProperties(env);
        SmsSender sender = config.smsSender(List.of(named("mboasms")), properties);

        assertThat(properties.providers()).isEmpty();
        assertThat(properties.senderId()).isEqualTo("BrefPoint");
        assertThat(sender.isEnabled()).isFalse();
        assertThat(config.emailSender(List.of(), config.emailChannelProperties(env)).isEnabled()).isFalse();
    }
}
