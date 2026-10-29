package com.keevo.shared.module.application;

import com.keevo.shared.module.BlockReason;
import com.keevo.shared.module.ModuleActivationEligibility;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.TenantModuleStatus;
import org.springframework.stereotype.Component;

/**
 * The single {@link ModuleActivationEligibility} bean (Story 13.5, replaces 13.3's {@code FreeModulesOnlyEligibility}):
 * a {@code free} module is eligible and would be activated {@code ACTIVE}; a paid module is not eligible
 * ({@code NOT_ENTITLED}) — trials and payments come with Story 16.1, which extends this bean.
 */
@Component
public class EntitlementActivationEligibility implements ModuleActivationEligibility {

    @Override
    public Eligibility check(String tenantSchema, ModuleManifest module) {
        return module.free() ? Eligibility.eligible(TenantModuleStatus.ACTIVE)
                : Eligibility.refused(BlockReason.Code.NOT_ENTITLED);
    }
}
