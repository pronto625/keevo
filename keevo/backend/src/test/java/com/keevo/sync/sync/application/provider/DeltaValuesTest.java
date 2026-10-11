package com.keevo.sync.sync.application.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1s-15-9: type-checked delta number/boolean conversion — never truncates. */
class DeltaValuesTest {

    @Test
    void num_nullIsZero() {
        assertThat(DeltaValues.num(null)).isEqualTo(0);
    }

    @Test
    void num_smallIntegralTypesBecomeInt() {
        assertThat(DeltaValues.num(7)).isEqualTo(7);
        assertThat(DeltaValues.num((short) 3)).isEqualTo(3);
        assertThat(DeltaValues.num((byte) 2)).isEqualTo(2);
        assertThat(DeltaValues.num(12L)).isEqualTo(12);
    }

    @Test
    void num_longBeyondIntIsKeptAsLong() {
        long big = Integer.MAX_VALUE + 10L;
        assertThat(DeltaValues.num(big)).isEqualTo(big);
        assertThat(DeltaValues.num(BigInteger.valueOf(big))).isEqualTo(big);
        assertThat(DeltaValues.num(BigDecimal.valueOf(big))).isEqualTo(big);
    }

    @Test
    void num_integralBigDecimalBecomesInt() {
        assertThat(DeltaValues.num(new BigDecimal("12"))).isEqualTo(12);
        assertThat(DeltaValues.num(new BigDecimal("12.000"))).isEqualTo(12);
        assertThat(DeltaValues.num(BigInteger.valueOf(-4))).isEqualTo(-4);
    }

    @Test
    void num_nonIntegralIsReturnedUnchanged() {
        BigDecimal fractional = new BigDecimal("12.5");
        assertThat(DeltaValues.num(fractional)).isSameAs(fractional);
        assertThat(DeltaValues.num(2.5d)).isEqualTo(2.5d);
        assertThat(DeltaValues.num(4.0d)).isEqualTo(4);
    }

    @Test
    void num_valueBeyondLongIsReturnedUnchanged() {
        BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        assertThat(DeltaValues.num(huge)).isSameAs(huge);
    }

    @Test
    void num_unknownTypeThrows() {
        assertThatThrownBy(() -> DeltaValues.num("12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bool_acceptsBooleansNumbersAndPostgresText() {
        assertThat(DeltaValues.bool(null)).isFalse();
        assertThat(DeltaValues.bool(Boolean.TRUE)).isTrue();
        assertThat(DeltaValues.bool(1)).isTrue();
        assertThat(DeltaValues.bool(0)).isFalse();
        assertThat(DeltaValues.bool(new BigDecimal("0.5"))).isTrue();
        assertThat(DeltaValues.bool(new BigDecimal("0.00"))).isFalse();
        assertThat(DeltaValues.bool(Long.MIN_VALUE)).isTrue();
        assertThat(DeltaValues.bool("t")).isTrue();
        assertThat(DeltaValues.bool("TRUE")).isTrue();
        assertThat(DeltaValues.bool("f")).isFalse();
        assertThat(DeltaValues.bool('t')).isTrue();
    }

    @Test
    void bool_unknownValueThrows() {
        assertThatThrownBy(() -> DeltaValues.bool("maybe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeltaValues.bool(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
