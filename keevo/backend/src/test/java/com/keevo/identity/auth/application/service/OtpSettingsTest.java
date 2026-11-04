package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.OtpIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OtpSettings (Story 19.2)")
class OtpSettingsTest {

    @Test
    @DisplayName("prefixes are parsed from the comma list (non-digits stripped); empty → defaults")
    void prefixes() {
        assertThat(new OtpSettings(" +237, 33 ,", "", "").allowedPhonePrefixes()).containsExactly("237", "33");
        assertThat(new OtpSettings("", "", "").allowedPhonePrefixes())
                .containsExactly("237", "241", "235", "243", "221");
    }

    @Test
    @DisplayName("review account: identifier normalized, code compared exactly")
    void review() {
        OtpSettings s = new OtpSettings("237", " Review@BrefPoint.test ", "246810");
        OtpIdentifier id = OtpIdentifier.parse("review@brefpoint.test", s.allowedPhonePrefixes());
        assertThat(s.isReviewIdentifier(id)).isTrue();
        assertThat(s.isReviewLogin(id, "246810")).isTrue();
        assertThat(s.isReviewLogin(id, "246811")).isFalse();
        assertThat(s.isReviewLogin(id, "2468100")).isFalse();
        assertThat(s.isReviewLogin(OtpIdentifier.parse("other@ex.com", s.allowedPhonePrefixes()), "246810")).isFalse();
    }

    @Test
    @DisplayName("review account disabled when the code is not ≥ 6 digits or a value is empty")
    void reviewDisabled() {
        OtpIdentifier id = OtpIdentifier.parse("review@brefpoint.test", java.util.List.of("237"));
        assertThat(new OtpSettings("237", "review@brefpoint.test", "12345").isReviewIdentifier(id)).isFalse();
        assertThat(new OtpSettings("237", "review@brefpoint.test", "abcdef").isReviewIdentifier(id)).isFalse();
        assertThat(new OtpSettings("237", "", "246810").isReviewIdentifier(id)).isFalse();
        assertThat(new OtpSettings("237", "review@brefpoint.test", "").isReviewLogin(id, "")).isFalse();
    }
}
