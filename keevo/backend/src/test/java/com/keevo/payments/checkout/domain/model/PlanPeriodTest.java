package com.keevo.payments.checkout.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlanPeriod — covered period of a confirmed payment (Story 21.1, ADR-06)")
class PlanPeriodTest {

    /** 2026-11-09 10:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    @Test
    @DisplayName("no running paid plan → from today for the cycle's months, expiry 23:59:59 WAT of the last day")
    void fromToday() {
        PlanPeriod p = PlanPeriod.of(false, Instant.parse("2026-12-31T22:59:59Z"), NOW, 1);
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(p.end()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-12-08T22:59:59Z"));
    }

    @Test
    @DisplayName("running paid plan until 30/11 → renewal from 01/12, quarterly until 28/02")
    void renewal() {
        PlanPeriod p = PlanPeriod.of(true, Instant.parse("2026-11-30T22:59:59Z"), NOW, 3);
        assertThat(p.start()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(p.end()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2027-02-28T22:59:59Z"));
    }

    @Test
    @DisplayName("paid plan ending today is extended from tomorrow; ended yesterday or no expiry → from today")
    void edges() {
        assertThat(PlanPeriod.of(true, Instant.parse("2026-11-09T22:59:59Z"), NOW, 1).start())
                .isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(PlanPeriod.of(true, Instant.parse("2026-11-08T22:59:59Z"), NOW, 1).start())
                .isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(PlanPeriod.of(true, null, NOW, 12).end()).isEqualTo(LocalDate.of(2027, 11, 8));
        assertThatThrownBy(() -> PlanPeriod.of(false, null, NOW, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
