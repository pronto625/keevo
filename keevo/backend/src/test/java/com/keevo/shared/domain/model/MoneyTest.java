package com.keevo.shared.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MoneyTest — TDD bootstrap test for the Money XAF value object.
 *
 * <p>Written FIRST (Red) per TDD mandate.
 * Money.java was then implemented to make these tests Green.
 */
@DisplayName("Money XAF value object")
class MoneyTest {

    // ── Creation ─────────────────────────────────────────────

    @Test
    @DisplayName("should create valid money with positive value")
    void should_create_valid_money() {
        Money money = new Money(5000);
        assertThat(money.value()).isEqualTo(5000);
    }

    @Test
    @DisplayName("should create money with zero value")
    void should_create_money_with_zero() {
        Money money = new Money(0);
        assertThat(money.value()).isEqualTo(0);
    }

    @Test
    @DisplayName("should reject negative amount with DomainException")
    void should_reject_negative_amount() {
        assertThatThrownBy(() -> new Money(-1))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INVALID_AMOUNT");
    }

    // ── toString ──────────────────────────────────────────────

    @Test
    @DisplayName("should display '5000 XAF' format")
    void should_format_as_xaf() {
        assertThat(new Money(5000).toString()).isEqualTo("5000 XAF");
    }

    @Test
    @DisplayName("should display '0 XAF' for zero")
    void should_format_zero_as_xaf() {
        assertThat(new Money(0).toString()).isEqualTo("0 XAF");
    }

    // ── Arithmetic ────────────────────────────────────────────

    @Test
    @DisplayName("should add two Money values")
    void should_add_money() {
        Money a = new Money(3000);
        Money b = new Money(2000);
        assertThat(a.add(b).value()).isEqualTo(5000);
    }

    @Test
    @DisplayName("should subtract smaller from larger")
    void should_subtract_money() {
        Money a = new Money(5000);
        Money b = new Money(2000);
        assertThat(a.subtract(b).value()).isEqualTo(3000);
    }

    @Test
    @DisplayName("should reject subtraction resulting in negative")
    void should_reject_negative_subtraction() {
        Money a = new Money(1000);
        Money b = new Money(2000);
        assertThatThrownBy(() -> a.subtract(b))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INVALID_AMOUNT");
    }

    @Test
    @DisplayName("should multiply by positive factor")
    void should_multiply_money() {
        Money price = new Money(2500);
        assertThat(price.multiply(3).value()).isEqualTo(7500);
    }

    @Test
    @DisplayName("should multiply by zero resulting in 0 XAF")
    void should_multiply_by_zero() {
        assertThat(new Money(5000).multiply(0).value()).isEqualTo(0);
    }

    @Test
    @DisplayName("should reject negative multiplication factor")
    void should_reject_negative_factor() {
        assertThatThrownBy(() -> new Money(5000).multiply(-1))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INVALID_AMOUNT");
    }

    // ── multiply(double) — for margin calculations ─────────

    @Test
    @DisplayName("should multiply by double factor with HALF_UP rounding")
    void should_multiply_by_double_factor() {
        // 1000 * 1.3 = 1300 (exact)
        assertThat(new Money(1000).multiply(1.3).value()).isEqualTo(1300);
    }

    @Test
    @DisplayName("should round HALF_UP when multiplying by double (0.5 rounds up)")
    void should_round_half_up_when_multiplying_by_double() {
        // 1000 * 1.15 = 1150 (exact)
        assertThat(new Money(1000).multiply(1.15).value()).isEqualTo(1150);
    }

    @Test
    @DisplayName("should round fractional XAF result HALF_UP from double multiply")
    void should_round_fractional_result_half_up() {
        // 3 * 0.33333... = 1 (rounds 0.9999... to 1)
        // More concrete: 100 * 1.115 = 111.5 → rounds to 112
        assertThat(new Money(100).multiply(1.115).value()).isEqualTo(112);
    }

    @Test
    @DisplayName("should return zero when multiplying by 0.0")
    void should_return_zero_when_multiplying_by_zero_double() {
        assertThat(new Money(5000).multiply(0.0).value()).isEqualTo(0);
    }

    @Test
    @DisplayName("should reject negative double multiplication factor")
    void should_reject_negative_double_factor() {
        assertThatThrownBy(() -> new Money(5000).multiply(-0.5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("INVALID_AMOUNT");
    }

    @Test
    @DisplayName("multiply double and multiply int should be consistent for integer factors")
    void should_be_consistent_for_integer_factors() {
        // 2500 * 3 == 2500 * 3.0
        assertThat(new Money(2500).multiply(3).value())
                .isEqualTo(new Money(2500).multiply(3.0).value());
    }

    // ── Equality ──────────────────────────────────────────────

    @Test
    @DisplayName("should be equal when values are the same")
    void should_be_equal_for_same_value() {
        assertThat(new Money(5000)).isEqualTo(new Money(5000));
    }

    @Test
    @DisplayName("should not be equal when values differ")
    void should_not_be_equal_for_different_values() {
        assertThat(new Money(5000)).isNotEqualTo(new Money(4999));
    }

    @Test
    @DisplayName("should have consistent hashCode")
    void should_have_consistent_hash_code() {
        assertThat(new Money(5000).hashCode()).isEqualTo(new Money(5000).hashCode());
    }
}
