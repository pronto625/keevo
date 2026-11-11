package com.keevo.whatsappcloud.consent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("STOP / START keywords (21.6, Bref handleIncomingMessage)")
class KeywordConsentTest {

    @ParameterizedTest
    @ValueSource(strings = {"STOP", " stop ", "Stop.", "arrêt", "ARRÊTER", "désabonner", "unsubscribe", "S-T-O-P"})
    @DisplayName("opt-out words, any case / accents / punctuation")
    void optOut(String text) {
        assertThat(KeywordConsent.parse(text)).contains(KeywordConsent.Answer.OPT_OUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"START", "start!", "Reprendre", "subscribe"})
    @DisplayName("opt-in words")
    void optIn(String text) {
        assertThat(KeywordConsent.parse(text)).contains(KeywordConsent.Answer.OPT_IN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop les promos svp", "Bonjour", "", "   ", "stopper", "je veux arrêter de recevoir vos messages"})
    @DisplayName("anything else is a normal message")
    void notKeywords(String text) {
        assertThat(KeywordConsent.parse(text)).isEmpty();
    }

    @Test
    @DisplayName("null and long texts are never keywords; confirmations in French for both numbers")
    void bounds() {
        assertThat(KeywordConsent.parse(null)).isEmpty();
        assertThat(KeywordConsent.parse("stop".repeat(20))).isEmpty();
        assertThat(KeywordConsent.confirmation(KeywordConsent.Answer.OPT_OUT, false)).contains("START");
        assertThat(KeywordConsent.confirmation(KeywordConsent.Answer.OPT_IN, false)).contains("STOP");
        assertThat(KeywordConsent.confirmation(KeywordConsent.Answer.OPT_OUT, true)).contains("codes de connexion");
    }
}
