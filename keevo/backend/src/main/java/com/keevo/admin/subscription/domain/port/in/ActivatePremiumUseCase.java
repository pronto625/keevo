package com.keevo.admin.subscription.domain.port.in;

import com.keevo.subscription.api.CurrentPlan;

/** ActivatePremiumUseCase — records the payment and sets the tenant on PREMIUM (Story 9-2). */
public interface ActivatePremiumUseCase {

    /** @return the new plan (PREMIUM + expiry instant) */
    CurrentPlan activatePremium(ActivatePremiumCommand command);
}
