package com.keevo.identity.auth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanTypeTest — Story 14.13: Verify FREE plan limits (FR16 revision 2026-03-06: 1 store, 500 products, 3 employees).
 */
@DisplayName("PlanType")
class PlanTypeTest {

    @Test
    @DisplayName("FREE plan should allow up to 1 store (FR16 — Story 14.13)")
    void freePlan_shouldAllowUpTo1Store() {
        assertThat(PlanType.FREE.getMaxStores()).isEqualTo(1);
    }

    @Test
    @DisplayName("PREMIUM_TRIAL plan should allow unlimited stores")
    void premiumTrialPlan_shouldAllowUnlimitedStores() {
        assertThat(PlanType.PREMIUM_TRIAL.getMaxStores()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("FREE plan should allow up to 500 products")
    void freePlan_shouldAllowUpTo500Products() {
        assertThat(PlanType.FREE.getMaxProducts()).isEqualTo(500);
    }

    @Test
    @DisplayName("FREE plan should allow up to 3 employees (FR16 — Story 14.13)")
    void freePlan_shouldAllowUpTo3Employees() {
        assertThat(PlanType.FREE.getMaxEmployees()).isEqualTo(3);
    }
}
