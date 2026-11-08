package com.keevo.messaging.channel.application;

import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.EmailProvider;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FailoverEmailSender — provider chain (Story 19.9)")
class FailoverEmailSenderTest {

    private final List<String> calls = new ArrayList<>();

    private EmailProvider provider(String name, boolean configured, SendOutcome outcome) {
        return new EmailProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public SendOutcome send(EmailMessage message, String sender) {
                calls.add(name + "|" + message.to() + "|" + sender);
                return outcome;
            }
        };
    }

    @Test
    @DisplayName("resend fails → infobip delivers; sender carries the message display name")
    void failover() {
        FailoverEmailSender sender = new FailoverEmailSender(
                List.of(provider("resend", true, SendOutcome.FAILED), provider("infobip", true, SendOutcome.DELIVERED)),
                "noreply@brefpoint.app", "Bref Point");

        DeliveryResult result = sender.send(new EmailMessage(" awa@example.cm ", "Facture", "Bonjour", null,
                "Boutique \"Awa\"", List.of()));

        assertThat(result).isEqualTo(DeliveryResult.deliveredBy("infobip"));
        assertThat(calls).containsExactly(
                "resend|awa@example.cm|\"Boutique 'Awa'\" <noreply@brefpoint.app>",
                "infobip|awa@example.cm|\"Boutique 'Awa'\" <noreply@brefpoint.app>");
    }

    @Test
    @DisplayName("default display name, or the bare address when none")
    void senderFormat() {
        assertThat(new FailoverEmailSender(List.of(), "noreply@brefpoint.app", "Bref Point").sender(null))
                .isEqualTo("\"Bref Point\" <noreply@brefpoint.app>");
        assertThat(new FailoverEmailSender(List.of(), "noreply@brefpoint.app", "").sender(" "))
                .isEqualTo("noreply@brefpoint.app");
    }

    @Test
    @DisplayName("a backslash in the display name is stripped: the quoted From stays well-formed")
    void backslashStripped() {
        assertThat(new FailoverEmailSender(List.of(), "noreply@x.cm", null).sender("Awa\\\" <evil@x.cm>"))
                .isEqualTo("\"Awa'  evil@x.cm \" <noreply@x.cm>");
    }

    @Test
    @DisplayName("unknown outcome → no failover, not delivered")
    void unknownStops() {
        FailoverEmailSender sender = new FailoverEmailSender(List.of(provider("resend", true, SendOutcome.UNKNOWN),
                provider("infobip", true, SendOutcome.DELIVERED)), "noreply@x.cm", null);

        DeliveryResult result = sender.send(EmailMessage.of("a@b.cm", "S", "T"));
        assertThat(result.delivered()).isFalse();
        assertThat(result.uncertain()).isTrue(); // Story 20.8
        assertThat(calls).extracting(c -> c.split("\\|")[0]).containsExactly("resend");
    }

    @Test
    @DisplayName("unconfigured skipped; all fail → not delivered")
    void allFail() {
        FailoverEmailSender sender = new FailoverEmailSender(
                List.of(provider("resend", false, SendOutcome.DELIVERED), provider("infobip", true, SendOutcome.FAILED)), "noreply@x.cm", null);

        assertThat(sender.send(EmailMessage.of("a@b.cm", "S", "T"))).isEqualTo(DeliveryResult.notDelivered());
        assertThat(calls).extracting(c -> c.split("\\|")[0]).containsExactly("infobip");
    }

    @Test
    @DisplayName("empty chain → disabled, no call; invalid address → no call")
    void disabledAndInvalid() {
        FailoverEmailSender disabled = new FailoverEmailSender(List.of(), "noreply@x.cm", null);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.send(EmailMessage.of("a@b.cm", "S", "T")).delivered()).isFalse();

        FailoverEmailSender enabled = new FailoverEmailSender(List.of(provider("resend", true, SendOutcome.DELIVERED)),
                "noreply@x.cm", null);
        assertThat(enabled.send(EmailMessage.of("not an email", "S", "T")).delivered()).isFalse();
        assertThat(calls).isEmpty();
    }
}
