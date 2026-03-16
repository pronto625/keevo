package com.keevo.identity.auth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanTypeTest — Story 3.1: Verify FREE plan allows up to 3 stores.
 */
@DisplayName("PlanType")
class PlanTypeTest {

    @Test
    @DisplayName("FREE plan should allow up to 3 stores (AC2 — Story 3.1)")
    void freePlan_shouldAllowUpTo3Stores() {
        assertThat(PlanType.FREE.getMaxStores()).isEqualTo(3);
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
    @DisplayName("FREE plan should allow up to 5 employees")
    void freePlan_shouldAllowUpTo5Employees() {
        assertThat(PlanType.FREE.getMaxEmployees()).isEqualTo(5);
    }
}
