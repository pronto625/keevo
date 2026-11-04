package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OtpIdentifier (Story 19.2)")
class OtpIdentifierTest {

    private static final List<String> PREFIXES = List.of("237", "241", "235", "243", "221");

    private static OtpIdentifier parse(String raw) {
        return OtpIdentifier.parse(raw, PREFIXES);
    }

    private static void assertError(String raw, ErrorCode code) {
        assertThatThrownBy(() -> parse(raw))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("phone with spaces → E.164, kind PHONE")
    void phoneWithSpaces() {
        OtpIdentifier id = parse("+237 6 99 00 11 22");
        assertThat(id.value()).isEqualTo("+237699001122");
        assertThat(id.kind()).isEqualTo(OtpIdentifier.Kind.PHONE);
    }

    @Test
    @DisplayName("0023799… and 00237… → +237…")
    void doubleZeroPrefix() {
        assertThat(parse("00237699001122").value()).isEqualTo("+237699001122");
        assertThat(parse("+00237699001122").value()).isEqualTo("+237699001122");
        assertThat(parse("237699001122").value()).isEqualTo("+237699001122");
    }

    @Test
    @DisplayName("email is trimmed and lowercased, kind EMAIL")
    void emailLowercased() {
        OtpIdentifier id = parse("  Ana@Ex.com ");
        assertThat(id.value()).isEqualTo("ana@ex.com");
        assertThat(id.isEmail()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+241061234567", "+235661234567", "+243812345678", "+221771234567"})
    @DisplayName("every default country is accepted")
    void allowedCountries(String phone) {
        assertThat(parse(phone).value()).isEqualTo(phone);
    }

    @Test
    @DisplayName("valid phone of another country → PHONE_COUNTRY_NOT_SUPPORTED")
    void otherCountry() {
        assertError("+33612345678", ErrorCode.PHONE_COUNTRY_NOT_SUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "  ", "+2376", "+2376990011223344", "ana@", "@ex.com", "ana@ex.c", "a b@ex.com"})
    @DisplayName("neither an email nor a phone → VALIDATION_FAILED")
    void invalid(String raw) {
        assertError(raw, ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("null and over-long email → VALIDATION_FAILED")
    void nullAndLongEmail() {
        assertError(null, ErrorCode.VALIDATION_FAILED);
        assertError("a".repeat(60) + "@" + "b".repeat(190) + ".com", ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("configured prefixes replace the defaults")
    void configuredPrefixes() {
        assertThat(OtpIdentifier.parse("+33612345678", List.of("33")).value()).isEqualTo("+33612345678");
        assertThatThrownBy(() -> OtpIdentifier.parse("+237699001122", List.of("33")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("channel rule: email → EMAIL only; phone → requested, or SMS + WHATSAPP")
    void channels() {
        OtpIdentifier email = parse("ana@ex.com");
        OtpIdentifier phone = parse("+237699001122");
        assertThat(email.channelsFor(null)).containsExactly(OtpChannel.EMAIL);
        assertThat(email.channelsFor(OtpChannel.EMAIL)).containsExactly(OtpChannel.EMAIL);
        assertThat(phone.channelsFor(null)).containsExactly(OtpChannel.SMS, OtpChannel.WHATSAPP);
        assertThat(phone.channelsFor(OtpChannel.SMS)).containsExactly(OtpChannel.SMS);
        assertThat(phone.channelsFor(OtpChannel.WHATSAPP)).containsExactly(OtpChannel.WHATSAPP);
        assertThatThrownBy(() -> email.channelsFor(OtpChannel.SMS)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> email.channelsFor(OtpChannel.WHATSAPP)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> phone.channelsFor(OtpChannel.EMAIL)).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("unknown channel → VALIDATION_FAILED; blank → null")
    void channelParse() {
        assertThat(OtpChannel.parseOptional(null)).isNull();
        assertThat(OtpChannel.parseOptional(" ")).isNull();
        assertThat(OtpChannel.parseOptional("whatsapp")).isEqualTo(OtpChannel.WHATSAPP);
        assertThatThrownBy(() -> OtpChannel.parseOptional("PIGEON")).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("masked form hides the identifier; toString is masked")
    void masked() {
        assertThat(parse("+237699001122").masked()).isEqualTo("+2376******22");
        assertThat(parse("ana@ex.com").masked()).isEqualTo("a***@ex.com");
        assertThat(parse("ana@ex.com").toString()).doesNotContain("ana@");
    }
}
