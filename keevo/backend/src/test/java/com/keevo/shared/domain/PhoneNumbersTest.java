package com.keevo.shared.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PhoneNumbers.normalize (Story 12.12)")
class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "+237699123456", "237699123456", " 237699123456 ", "+237 699 12 34 56",
            "237-699-123-456", "(237) 699.123.456", "\t+237699123456\n"
    })
    @DisplayName("formatting variants collapse to +<digits>")
    void variantsCollapse(String raw) {
        assertThat(PhoneNumbers.normalize(raw)).isEqualTo("+237699123456");
    }

    @Test
    @DisplayName("null stays null")
    void nullStaysNull() {
        assertThat(PhoneNumbers.normalize(null)).isNull();
    }

    @Test
    @DisplayName("blank becomes empty, no lone '+'")
    void blankBecomesEmpty() {
        assertThat(PhoneNumbers.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("idempotent")
    void idempotent() {
        String once = PhoneNumbers.normalize("237 699 123 456");
        assertThat(PhoneNumbers.normalize(once)).isEqualTo(once);
    }
}
