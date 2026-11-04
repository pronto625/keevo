package com.keevo.messaging.whatsapp.application;

import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.whatsapp.domain.exception.WhatsAppDeliveryException;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 19.9b — the WhatsApp chain: every WhatsApp row of the spec matrix. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ChainWhatsAppSender — Meta template first, Wassender→Twilio fallback (Story 19.9b)")
class ChainWhatsAppSenderTest {

    private static final String PHONE = "+237699000080";
    private static final String CODE = "482913";
    private static final String TEXT = "Votre code de connexion Bref Point : 482913.";

    /** Scripted provider recording its calls. */
    static class Fake implements WhatsAppProvider {
        final String name;
        final boolean templates;
        boolean configured = true;
        final Deque<SendOutcome> outcomes = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        Fake(String name, boolean templates, SendOutcome... outcomes) {
            this.name = name;
            this.templates = templates;
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override public String name() { return name; }
        @Override public boolean isConfigured() { return configured; }
        @Override public boolean supportsTemplates() { return templates; }

        @Override
        public SendOutcome sendText(String phone, String text) {
            calls.add("text:" + phone + ":" + text);
            return outcomes.isEmpty() ? SendOutcome.DELIVERED : outcomes.poll();
        }

        @Override
        public SendOutcome sendCode(String phone, String code, String language) {
            calls.add("code:" + phone + ":" + code + ":" + language);
            return outcomes.isEmpty() ? SendOutcome.DELIVERED : outcomes.poll();
        }
    }

    private static ChainWhatsAppSender chain(WhatsAppProvider... providers) {
        return new ChainWhatsAppSender(List.of(providers));
    }

    @Test
    @DisplayName("code, Meta OK: one Meta call in fr, no fallback")
    void metaOk() {
        Fake meta = new Fake("meta", true, SendOutcome.DELIVERED);
        Fake wassender = new Fake("wassender", false);
        chain(meta, wassender, new Fake("twilio", false)).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(meta.calls).containsExactly("code:" + PHONE + ":" + CODE + ":fr");
        assertThat(wassender.calls).isEmpty();
    }

    @Test
    @DisplayName("code, Meta fails (HTTP error): fallback text through Wassender")
    void metaFailsFallsBackToWassender() {
        Fake meta = new Fake("meta", true, SendOutcome.FAILED);
        Fake wassender = new Fake("wassender", false, SendOutcome.DELIVERED);
        Fake twilio = new Fake("twilio", false);
        chain(meta, wassender, twilio).sendCode(PHONE, CODE, TEXT, "en");
        assertThat(meta.calls).containsExactly("code:" + PHONE + ":" + CODE + ":en");
        assertThat(wassender.calls).containsExactly("text:" + PHONE + ":" + TEXT);
        assertThat(twilio.calls).isEmpty();
    }

    @Test
    @DisplayName("code, Meta and Wassender fail: Twilio")
    void wassenderFailsTwilio() {
        Fake twilio = new Fake("twilio", false, SendOutcome.DELIVERED);
        chain(new Fake("meta", true, SendOutcome.FAILED), new Fake("wassender", false, SendOutcome.FAILED), twilio)
                .sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(twilio.calls).containsExactly("text:" + PHONE + ":" + TEXT);
    }

    @Test
    @DisplayName("code, Meta not configured: no Meta call, fallback text")
    void metaNotConfigured() {
        Fake meta = new Fake("meta", true);
        meta.configured = false;
        Fake wassender = new Fake("wassender", false, SendOutcome.DELIVERED);
        chain(meta, wassender).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(meta.calls).isEmpty();
        assertThat(wassender.calls).hasSize(1);
    }

    @Test
    @DisplayName("code, Meta read timeout: unknown outcome, no fallback, WARN")
    void metaTimeoutNoFallback(CapturedOutput output) {
        Fake wassender = new Fake("wassender", false);
        chain(new Fake("meta", true, SendOutcome.UNKNOWN), wassender).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(wassender.calls).isEmpty();
        assertThat(output.getAll()).contains("outcome unknown").contains("***080");
    }

    @Test
    @DisplayName("code, every provider fails: WhatsAppDeliveryException naming the providers, masked phone")
    void allFail() {
        assertThatThrownBy(() -> chain(new Fake("meta", true, SendOutcome.FAILED),
                new Fake("wassender", false, SendOutcome.FAILED), new Fake("twilio", false, SendOutcome.FAILED))
                .sendCode(PHONE, CODE, TEXT, "fr"))
                .isInstanceOf(WhatsAppDeliveryException.class)
                .hasMessageContaining("wassender").hasMessageContaining("twilio")
                .hasMessageNotContaining("699000080");
    }

    @Test
    @DisplayName("logs never carry the code nor the full phone")
    void logsWithoutCode(CapturedOutput output) {
        chain(new Fake("meta", true, SendOutcome.FAILED), new Fake("wassender", false, SendOutcome.DELIVERED))
                .sendCode(PHONE, CODE, TEXT, "fr");
        chain(new Fake("meta", true, SendOutcome.DELIVERED)).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(output.getAll()).doesNotContain(CODE).doesNotContain("699000080");
    }

    @Test
    @DisplayName("free text with the meta preset: Wassender→Twilio, never Meta")
    void reportNeverMeta() {
        Fake meta = new Fake("meta", true);
        Fake wassender = new Fake("wassender", false, SendOutcome.FAILED);
        Fake twilio = new Fake("twilio", false, SendOutcome.DELIVERED);
        chain(meta, wassender, twilio).sendReport(PHONE, "Rapport");
        assertThat(meta.calls).isEmpty();
        assertThat(wassender.calls).hasSize(1);
        assertThat(twilio.calls).hasSize(1);
    }

    @Test
    @DisplayName("free text, both fail: WhatsAppDeliveryException (as before)")
    void reportBothFail() {
        assertThatThrownBy(() -> chain(new Fake("wassender", false, SendOutcome.FAILED),
                new Fake("twilio", false, SendOutcome.FAILED)).sendReport(PHONE, "Rapport"))
                .isInstanceOf(WhatsAppDeliveryException.class);
    }

    @Test
    @DisplayName("free text, Wassender without token: skipped, Twilio called")
    void reportSkipsUnconfigured() {
        Fake wassender = new Fake("wassender", false);
        wassender.configured = false;
        Fake twilio = new Fake("twilio", false, SendOutcome.DELIVERED);
        chain(wassender, twilio).sendReport(PHONE, "Rapport");
        assertThat(wassender.calls).isEmpty();
        assertThat(twilio.calls).hasSize(1);
    }

    @Test
    @DisplayName("a provider throwing is a failure: next provider")
    void providerThrows() {
        WhatsAppProvider broken = new Fake("wassender", false) {
            @Override public SendOutcome sendText(String phone, String text) { throw new IllegalStateException(); }
        };
        Fake twilio = new Fake("twilio", false, SendOutcome.DELIVERED);
        chain(broken, twilio).sendReport(PHONE, "Rapport");
        assertThat(twilio.calls).hasSize(1);
    }

    @Test
    @DisplayName("phone sanitized once: spaces, dashes, parentheses stripped, + prefixed")
    void sanitizes() {
        Fake wassender = new Fake("wassender", false);
        chain(wassender).sendReport("237 (699) 00-00-80", "x");
        assertThat(wassender.calls).containsExactly("text:+237699000080:x");
    }

    @Test
    @DisplayName("language: en only when it says English, French otherwise")
    void language() {
        assertThat(ChainWhatsAppSender.language("en-US")).isEqualTo("en");
        assertThat(ChainWhatsAppSender.language("fr")).isEqualTo("fr");
        assertThat(ChainWhatsAppSender.language(null)).isEqualTo("fr");
    }

    @Test
    @DisplayName("isConfigured: a real provider with keys; the noop stub never counts")
    void configured() {
        Fake noop = new Fake("noop", false);
        assertThat(chain(noop).isConfigured()).isFalse();
        Fake wassender = new Fake("wassender", false);
        wassender.configured = false;
        Fake twilio = new Fake("twilio", false);
        twilio.configured = false;
        assertThat(chain(wassender, twilio).isConfigured()).isFalse();
        twilio.configured = true;
        assertThat(chain(wassender, twilio).isConfigured()).isTrue();
        assertThat(chain(new Fake("meta", true)).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("meta with only Meta keys: codes can go out, free text cannot (isConfigured false)")
    void metaOnly() {
        Fake wassender = new Fake("wassender", false);
        wassender.configured = false;
        Fake twilio = new Fake("twilio", false);
        twilio.configured = false;
        ChainWhatsAppSender sender = chain(new Fake("meta", true), wassender, twilio);
        assertThat(sender.isConfigured()).isFalse();
        assertThat(sender.canSendCodes()).isTrue();
        assertThat(chain(new Fake("noop", false)).canSendCodes()).isFalse();
        twilio.configured = true;
        assertThat(chain(new Fake("meta", true), wassender, twilio).isConfigured()).isTrue();
    }

    @Test
    @DisplayName("code fallback through sendCodeText: an unknown outcome (timeout) stops the chain, no second code")
    void fallbackTimeoutStops() {
        Fake wassender = new Fake("wassender", false) {
            @Override public SendOutcome sendCodeText(String phone, String text) {
                calls.add("codeText");
                return SendOutcome.UNKNOWN;
            }
        };
        Fake twilio = new Fake("twilio", false);
        chain(new Fake("meta", true, SendOutcome.FAILED), wassender, twilio).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(wassender.calls).containsExactly("codeText");
        assertThat(twilio.calls).isEmpty();
    }

    @Test
    @DisplayName("noop preset: a code goes to the stub as text, nothing thrown")
    void noopCode() {
        Fake noop = new Fake("noop", false);
        chain(noop).sendCode(PHONE, CODE, TEXT, "fr");
        assertThat(noop.calls).hasSize(1);
    }
}
