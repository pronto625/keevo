package com.keevo.messaging.channel.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Recipients — SMS / email normalization and masking (Story 19.9)")
class SmsRecipientsTest {

    @Test
    @DisplayName("spaces, dashes and dots are stripped before the E.164 check")
    void phoneNormalized() {
        assertThat(Recipients.normalizePhone("+237 699-00.00 01")).contains("+237699000001");
        assertThat(Recipients.normalizePhone("+23769900")).contains("+23769900");
    }

    @Test
    @DisplayName("parentheses stripped and a leading 00 read as +")
    void commonFormats() {
        assertThat(Recipients.normalizePhone("(+237) 699 00 01 23")).contains("+237699000123");
        assertThat(Recipients.normalizePhone("00237 699-00-01-23")).contains("+237699000123");
        assertThat(Recipients.normalizePhone("(00237) 699.000.123")).contains("+237699000123");
    }

    @ParameterizedTest(name = "invalid phone [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"6990", "237699000001", "699000123", "(699) 000 123", "0699000123", "+2376990", "+1234567890123456", "+237 699 00 00 0a"})
    void phoneInvalid(String phone) {
        assertThat(Recipients.normalizePhone(phone)).isEmpty();
    }

    @Test
    @DisplayName("sender id: non-alphanumerics stripped, 11 characters at most, default when nothing is left")
    void senderId() {
        assertThat(Recipients.normalizeSenderId("Ma Boutique-Douala!", "BrefPoint")).isEqualTo("MaBoutiqueD");
        assertThat(Recipients.normalizeSenderId("Shop", "BrefPoint")).isEqualTo("Shop");
        assertThat(Recipients.normalizeSenderId("--- !", "BrefPoint")).isEqualTo("BrefPoint");
        assertThat(Recipients.normalizeSenderId(null, "BrefPoint")).isEqualTo("BrefPoint");
        assertThat(Recipients.normalizeSenderId(null, null)).isNull();
    }

    @Test
    @DisplayName("a phone keeps only its last 3 digits, an email its first letter and domain")
    void masking() {
        assertThat(Recipients.maskPhone("+237699000123")).isEqualTo("***123");
        assertThat(Recipients.maskPhone("12")).isEqualTo("***");
        assertThat(Recipients.maskPhone(null)).isEqualTo("***");
        assertThat(Recipients.maskEmail("jean.dupont@example.com")).isEqualTo("j***@example.com");
        assertThat(Recipients.maskEmail("not-an-email")).isEqualTo("***");
        assertThat(Recipients.maskEmail(null)).isEqualTo("***");
    }

    @Test
    @DisplayName("email address plausibility")
    void emailAddress() {
        assertThat(Recipients.isEmailAddress("a@b.cm")).isTrue();
        assertThat(Recipients.isEmailAddress(" a@b.cm ")).isTrue();
        assertThat(Recipients.isEmailAddress("a@@b.cm")).isFalse();
        assertThat(Recipients.isEmailAddress("a b@c.cm")).isFalse();
        assertThat(Recipients.isEmailAddress("@b.cm")).isFalse();
        assertThat(Recipients.isEmailAddress("")).isFalse();
    }
}
