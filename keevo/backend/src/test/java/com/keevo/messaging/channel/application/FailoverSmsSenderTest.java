package com.keevo.messaging.channel.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FailoverSmsSender — provider chain (Story 19.9)")
class FailoverSmsSenderTest {

    private static final String PHONE = "+237 699 000 123";
    private static final String TEXT = "Votre code est 482913";

    private final List<String> calls = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(FailoverSmsSender.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(FailoverSmsSender.class)).detachAppender(logs);
    }

    private SmsProvider provider(String name, boolean configured, SendOutcome outcome) {
        return new SmsProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public SendOutcome send(String phone, String text, String senderId) {
                calls.add(name + ":" + phone + ":" + senderId);
                if (outcome == null) throw new IllegalStateException("boom " + phone);
                return outcome;
            }
        };
    }

    @Test
    @DisplayName("first provider fails → the next one delivers, phone normalized, default sender id")
    void failover() {
        FailoverSmsSender sender = new FailoverSmsSender(
                List.of(provider("mboasms", true, SendOutcome.FAILED), provider("infobip", true, SendOutcome.DELIVERED)), "BrefPoint");

        DeliveryResult result = sender.send(SmsMessage.of(PHONE, TEXT));

        assertThat(result).isEqualTo(DeliveryResult.deliveredBy("infobip"));
        assertThat(calls).containsExactly("mboasms:+237699000123:BrefPoint", "infobip:+237699000123:BrefPoint");
    }

    @Test
    @DisplayName("the configured order is respected: the first success stops the chain")
    void order() {
        FailoverSmsSender sender = new FailoverSmsSender(
                List.of(provider("infobip", true, SendOutcome.DELIVERED), provider("mboasms", true, SendOutcome.DELIVERED)), "BrefPoint");

        assertThat(sender.send(new SmsMessage(PHONE, TEXT, "Ma Boutique")).provider()).isEqualTo("infobip");
        assertThat(calls).containsExactly("infobip:+237699000123:MaBoutique");
    }

    @Test
    @DisplayName("an unconfigured provider is skipped without a call; an exception passes to the next")
    void skipAndException() {
        FailoverSmsSender sender = new FailoverSmsSender(List.of(provider("mboasms", false, SendOutcome.DELIVERED),
                provider("broken", true, null), provider("infobip", true, SendOutcome.DELIVERED)), "BrefPoint");

        assertThat(sender.send(SmsMessage.of(PHONE, TEXT)).provider()).isEqualTo("infobip");
        assertThat(calls).extracting(c -> c.split(":")[0]).containsExactly("broken", "infobip");
        assertThat(warnings()).anyMatch(m -> m.contains("mboasms skipped"))
                .anyMatch(m -> m.equals("[SMS] broken failed (IllegalStateException)"));
    }

    @Test
    @DisplayName("all providers fail → not delivered; logs never carry the text nor the full phone")
    void allFail() {
        FailoverSmsSender sender = new FailoverSmsSender(
                List.of(provider("mboasms", true, SendOutcome.FAILED), provider("infobip", true, null)), "BrefPoint");

        assertThat(sender.send(SmsMessage.of(PHONE, TEXT))).isEqualTo(DeliveryResult.notDelivered());
        assertThat(warnings()).isNotEmpty().noneMatch(m -> m.contains("482913") || m.contains("699000123"));
        assertThat(warnings()).anyMatch(m -> m.contains("***123"));
    }

    @Test
    @DisplayName("unknown outcome (read timeout) → chain stops, next provider never called, not delivered")
    void unknownStopsTheChain() {
        FailoverSmsSender sender = new FailoverSmsSender(
                List.of(provider("mboasms", true, SendOutcome.UNKNOWN), provider("infobip", true, SendOutcome.DELIVERED)),
                "BrefPoint");

        assertThat(sender.send(SmsMessage.of(PHONE, TEXT))).isEqualTo(DeliveryResult.notDelivered());
        assertThat(calls).extracting(c -> c.split(":")[0]).containsExactly("mboasms");
    }

    @Test
    @DisplayName("empty chain → disabled, not delivered, no call")
    void disabled() {
        FailoverSmsSender sender = new FailoverSmsSender(List.of(), "BrefPoint");

        assertThat(sender.isEnabled()).isFalse();
        assertThat(sender.send(SmsMessage.of(PHONE, TEXT)).delivered()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("invalid phone → not delivered, no provider called, WARN with the masked value")
    void invalidPhone() {
        FailoverSmsSender sender = new FailoverSmsSender(List.of(provider("mboasms", true, SendOutcome.DELIVERED)), "BrefPoint");

        assertThat(sender.send(SmsMessage.of("6990", TEXT)).delivered()).isFalse();
        assertThat(calls).isEmpty();
        assertThat(warnings()).containsExactly("[SMS] invalid phone ***990 — not sent");
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
