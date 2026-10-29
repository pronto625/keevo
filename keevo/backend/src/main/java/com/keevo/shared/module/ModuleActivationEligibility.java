package com.keevo.shared.module;

/**
 * Whether a tenant may activate a module — the entitlement step of an activation plan (Story 13.3). Until Story
 * 13.5 the only implementation is {@code shared.module.application.FreeModulesOnlyEligibility} (free modules are
 * eligible, paid ones are not); 13.5 replaces that bean with the real entitlement check.
 */
public interface ModuleActivationEligibility {

    /** Whether the tenant ({@code kv_xxxxxx}) may activate {@code module}. */
    Eligibility check(String tenantSchema, ModuleManifest module);

    /**
     * Outcome of an eligibility check.
     *
     * @param eligible whether the module may be activated
     */
    record Eligibility(boolean eligible) {

        public static final Eligibility ELIGIBLE = new Eligibility(true);
        public static final Eligibility NOT_ENTITLED = new Eligibility(false);
    }
}
