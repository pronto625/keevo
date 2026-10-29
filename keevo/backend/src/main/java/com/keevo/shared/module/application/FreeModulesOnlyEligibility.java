package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleActivationEligibility;
import com.keevo.shared.module.ModuleManifest;
import org.springframework.stereotype.Component;

/**
 * Default {@link ModuleActivationEligibility} until Story 13.5 (no payment flow yet): a {@code free} module is
 * eligible, a paid module is not. 13.5 replaces this bean with the entitlement check.
 */
@Component
public class FreeModulesOnlyEligibility implements ModuleActivationEligibility {

    @Override
    public Eligibility check(String tenantSchema, ModuleManifest module) {
        return module.free() ? Eligibility.ELIGIBLE : Eligibility.NOT_ENTITLED;
    }
}
