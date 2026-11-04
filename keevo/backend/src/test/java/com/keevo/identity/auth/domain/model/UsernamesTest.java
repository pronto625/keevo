package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("19.4 — Usernames (Bref username rule)")
class UsernamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"awa", "awa_k", "Jean.Dupont", "x-1_2.3", "690000001",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwx", "googlex", "apple_x"})
    @DisplayName("3–50 letters, digits, '.', '_', '-' are valid (all digits too)")
    void valid(String username) {
        assertThat(Usernames.isValid(username)).isTrue();
        assertThat(Usernames.requireValid(username)).isEqualTo(username);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a b", "jean@dupont", "+237690000001", "été",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxy", "apple-x", "Google-abc", "APPLE-1"})
    @DisplayName("too short / too long / bad characters / reserved prefix → VALIDATION_FAILED with the French message")
    void invalid(String username) {
        assertThat(Usernames.isValid(username)).isFalse();
        assertThatThrownBy(() -> Usernames.requireValid(username))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getDomainCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
                    assertThat(d.getUserMessage())
                            .isEqualTo("Identifiant invalide (3 à 50 lettres, chiffres, point, tiret ou souligné)");
                });
    }

    @Test
    @DisplayName("requireValid strips; null and blank are invalid")
    void stripsAndRejectsBlank() {
        assertThat(Usernames.requireValid("  awa_k ")).isEqualTo("awa_k");
        assertThat(Usernames.isValid(null)).isFalse();
        assertThatThrownBy(() -> Usernames.requireValid("   ")).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("technical usernames of social accounts are recognised in any case")
    void technical() {
        assertThat(Usernames.isTechnical("google-ab12")).isTrue();
        assertThat(Usernames.isTechnical("Apple-ab12")).isTrue();
        assertThat(Usernames.isTechnical("awa")).isFalse();
        assertThat(Usernames.isTechnical(null)).isFalse();
    }
}
