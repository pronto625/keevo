package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PlanLimitGuardTest {

    private final PlanLimitGuard guard = new PlanLimitGuard();

    @Test
    void storeUnderLimit_passes() {
        assertThatCode(() -> guard.checkStoreLimit(PlanType.FREE, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void storeAtLimit_throwsPlanLimitExceeded() {
        assertThatThrownBy(() -> guard.checkStoreLimit(PlanType.FREE, 1))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> {
                    DomainException de = (DomainException) ex;
                    assertThat(de.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(de.getDetails().get("entity")).isEqualTo("stores");
                    assertThat(de.getDetails().get("limit")).isEqualTo(1);
                    assertThat(de.getDetails().get("current")).isEqualTo(1);
                });
    }

    @Test
    void productAtLimit_throwsPlanLimitExceeded() {
        assertThatThrownBy(() -> guard.checkProductLimit(PlanType.FREE, 500))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> {
                    DomainException de = (DomainException) ex;
                    assertThat(de.getDetails().get("entity")).isEqualTo("products");
                    assertThat(de.getDetails().get("limit")).isEqualTo(500);
                });
    }

    @Test
    void employeeAtLimit_throwsPlanLimitExceeded() {
        assertThatThrownBy(() -> guard.checkEmployeeLimit(PlanType.FREE, 3))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> {
                    DomainException de = (DomainException) ex;
                    assertThat(de.getDetails().get("entity")).isEqualTo("employees");
                    assertThat(de.getDetails().get("limit")).isEqualTo(3);
                });
    }

    @Test
    void premiumTrialPlan_neverBlocks() {
        assertThatCode(() -> {
            guard.checkStoreLimit(PlanType.PREMIUM_TRIAL, 999);
            guard.checkProductLimit(PlanType.PREMIUM_TRIAL, 99999);
            guard.checkEmployeeLimit(PlanType.PREMIUM_TRIAL, 9999);
        }).doesNotThrowAnyException();
    }

    @Test
    void premiumPlan_neverBlocks() {
        assertThatCode(() -> {
            guard.checkStoreLimit(PlanType.PREMIUM, 999);
            guard.checkProductLimit(PlanType.PREMIUM, 99999);
            guard.checkEmployeeLimit(PlanType.PREMIUM, 9999);
        }).doesNotThrowAnyException();
    }
}
