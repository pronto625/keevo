package com.keevo.identity.auth.domain.model;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlanTypeTest — Story 19.7a: a plan is a code of the catalogue (value type), not a closed enum; no limit lives in
 * it any more (the profiles are data). The seeded codes keep their constants; the kind gives the V1 code.
 */
@DisplayName("PlanType (Story 19.7a — plans are data)")
class PlanTypeTest {

    @Test
    @DisplayName("any well-formed code is a plan type; the seeded codes return their constant")
    void anyCode() {
        PlanType pro = PlanType.of("TEST_PAID");
        assertThat(pro.name()).isEqualTo("TEST_PAID");
        assertThat(pro).isEqualTo(PlanType.of("TEST_PAID")).hasSameHashCodeAs(PlanType.of("TEST_PAID"));
        assertThat(pro.seedKind()).isEmpty();
        assertThat(PlanType.of("PREMIUM")).isSameAs(PlanType.PREMIUM);
        assertThat(PlanType.PREMIUM_TRIAL.seedKind()).contains(PlanKind.TRIAL);
        assertThat(PlanType.FREE.toString()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("a malformed code is refused without echoing it")
    void malformed() {
        for (String bad : new String[] {null, "", "free", "X", "1ABC", "A".repeat(31), "PRO-1"}) {
            assertThatThrownBy(() -> PlanType.of(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid plan code");
            assertThat(PlanType.parse(bad)).isEmpty();
        }
        assertThat(PlanType.parse("A".repeat(30))).isPresent();
    }

    @Test
    @DisplayName("V1 compatibility: FREE → FREE, TRIAL → PREMIUM_TRIAL, PAID → PREMIUM; trial and paid expire")
    void v1Mapping() {
        assertThat(PlanKind.FREE.v1Plan()).isEqualTo(PlanType.FREE);
        assertThat(PlanKind.TRIAL.v1Plan()).isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(PlanKind.PAID.v1Plan()).isEqualTo(PlanType.PREMIUM);
        assertThat(PlanKind.FREE.expires()).isFalse();
        assertThat(PlanKind.TRIAL.expires()).isTrue();
        assertThat(PlanKind.PAID.expires()).isTrue();
    }
}
