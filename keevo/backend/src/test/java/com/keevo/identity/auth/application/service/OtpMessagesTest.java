package com.keevo.identity.auth.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OtpMessages (Story 19.2)")
class OtpMessagesTest {

    @Test
    @DisplayName("French by default, English when Accept-Language starts with en")
    void locale() {
        assertThat(OtpMessages.resolve(null)).isEqualTo(OtpMessages.Lang.FR);
        assertThat(OtpMessages.resolve("fr-CM,fr;q=0.9")).isEqualTo(OtpMessages.Lang.FR);
        assertThat(OtpMessages.resolve("ln")).isEqualTo(OtpMessages.Lang.FR);
        assertThat(OtpMessages.resolve("en-US,en;q=0.8")).isEqualTo(OtpMessages.Lang.EN);
        assertThat(OtpMessages.resolve("EN")).isEqualTo(OtpMessages.Lang.EN);
    }

    @Test
    @DisplayName("SMS texts are Bref's")
    void sms() {
        assertThat(OtpMessages.sms(OtpMessages.Lang.FR, "123456"))
                .isEqualTo("Votre code de connexion Bref Point : 123456. Valable 5 minutes. Ne le partagez pas.");
        assertThat(OtpMessages.sms(OtpMessages.Lang.EN, "123456"))
                .isEqualTo("Your Bref Point login code: 123456. Valid for 5 minutes. Do not share it.");
    }

    @Test
    @DisplayName("email HTML escapes the code and loads no external asset")
    void html() {
        String html = OtpMessages.emailHtml(OtpMessages.Lang.FR, "<b>&");
        assertThat(html).contains("&lt;b&gt;&amp;").doesNotContain("<b>&")
                .doesNotContain("http://").doesNotContain("https://").doesNotContain("<img");
        assertThat(OtpMessages.emailSubject(OtpMessages.Lang.FR)).isEqualTo("Votre code de vérification Bref Point");
        assertThat(OtpMessages.emailText(OtpMessages.Lang.EN, "654321")).contains("654321");
    }
}
