package com.keevo.payments.appstore.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XafAmount — XAF equivalent of a store price (Story 21.2)")
class XafAmountTest {

    @Test
    @DisplayName("XAF as is, EUR at the fixed peg, other currencies unknown; the ledger falls back on the declared price")
    void conversions() {
        assertThat(XafAmount.of(new BigDecimal("2500"), "XAF")).isEqualTo(2500L);
        assertThat(XafAmount.of(new BigDecimal("4.99"), "eur")).isEqualTo(3273L); // 4.99 × 655.957 = 3273.23
        assertThat(XafAmount.of(new BigDecimal("4.99"), "USD")).isNull();
        assertThat(XafAmount.of(BigDecimal.ZERO, "XAF")).isNull();
        assertThat(XafAmount.of(new BigDecimal("-3"), "XAF")).isNull();
        assertThat(XafAmount.ledger(new BigDecimal("4.99"), "USD", 3000L)).isEqualTo(3000L);
        assertThat(XafAmount.ledger(new BigDecimal("4.99"), "USD", null)).isNull();
        assertThat(XafAmount.ledger(new BigDecimal("2500"), "XAF", 3000L)).isEqualTo(2500L);
        assertThat(XafAmount.ledger(null, "XAF", 3000L)).isNull();
    }
}
