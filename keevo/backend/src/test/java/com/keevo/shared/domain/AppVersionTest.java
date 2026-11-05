package com.keevo.shared.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("19.10 — AppVersion")
class AppVersionTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "2.1.0, 2.1.0",
            "2, 2.0.0",
            "2.1, 2.1.0",
            "2.2.0+80, 2.2.0",
            "2.0.0-beta.1, 2.0.0",
            "2.0.0-rc1+12, 2.0.0",
            "' 1.0 ', 1.0.0",
            "10.20.30, 10.20.30",
            "007.1.0, 7.1.0"
    })
    @DisplayName("valid versions, missing parts = 0, suffix ignored")
    void parses(String text, String expected) {
        assertThat(AppVersion.tryParse(text)).get().hasToString(expected);
        assertThat(AppVersion.isValid(text)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "v2.0.0", "2.", ".2", "2..0", "2.0.0.1", "2.0.0-", "2.0.0+", "-1.0",
            "1.0 beta", "1,0", "9999999999.0", " "})
    @DisplayName("anything else is invalid")
    void refuses(String text) {
        assertThat(AppVersion.tryParse(text)).isEmpty();
        assertThat(AppVersion.isValid(text)).isFalse();
    }

    @Test
    @DisplayName("isRelease: plain MAJOR[.MINOR[.PATCH]] only (stored versions)")
    void release() {
        assertThat(AppVersion.isRelease("2.1.0")).isTrue();
        assertThat(AppVersion.isRelease(" 2.1 ")).isTrue();
        assertThat(AppVersion.isRelease("2.1.0+80")).isFalse();
        assertThat(AppVersion.isRelease("2.1.0-beta")).isFalse();
        assertThat(AppVersion.isRelease("abc")).isFalse();
        assertThat(AppVersion.isRelease(null)).isFalse();
    }

    @Test
    @DisplayName("over-long text is invalid")
    void refusesOverLong() {
        assertThat(AppVersion.isValid("1.0.0-" + "a".repeat(AppVersion.MAX_LENGTH))).isFalse();
    }

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
            "2.0.0, 2.1.0",
            "2.9.0, 2.10.0",
            "1.99.99, 2",
            "2.1.4, 2.1.5",
            "2.0, 2.0.1"
    })
    @DisplayName("numeric comparison")
    void compares(String older, String newer) {
        AppVersion a = AppVersion.tryParse(older).orElseThrow();
        AppVersion b = AppVersion.tryParse(newer).orElseThrow();
        assertThat(a.isBefore(b)).isTrue();
        assertThat(b.isBefore(a)).isFalse();
        assertThat(a).isLessThan(b);
    }

    @Test
    @DisplayName("equal when only the suffix or trailing zeros differ")
    void equalities() {
        AppVersion a = AppVersion.tryParse("2.2.0+80").orElseThrow();
        AppVersion b = AppVersion.tryParse("2.2").orElseThrow();
        assertThat(a).isEqualTo(b);
        assertThat(a.isBefore(b)).isFalse();
        assertThat(a.compareTo(b)).isZero();
    }
}
