package com.keevo.identity.auth.domain.model;

import com.keevo.identity.api.PlanType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanTypeTest — the built-in FREE limits (1 store, 500 products, 2 employees — journal I14). Since Story 16.8 they
 * are only the fallback of an unreadable {@code public.plan_profiles} row.
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
    @DisplayName("FREE plan fallback allows up to 2 employees (validated value, 16.8)")
    void freePlan_shouldAllowUpTo2Employees() {
        assertThat(PlanType.FREE.getMaxEmployees()).isEqualTo(2);
    }
}
