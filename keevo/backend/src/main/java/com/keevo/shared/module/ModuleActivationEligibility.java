package com.keevo.shared.module;

import java.util.Objects;

/**
 * Whether a tenant may activate a module — the entitlement step of an activation plan (Story 13.3). Since Story 13.5
 * the single implementation is {@code shared.module.application.EntitlementActivationEligibility} (a free module is
 * eligible, initial status ACTIVE; a paid one is not until trials and payments exist, Story 16.1).
 *
 * <p>Contract: an implementation never returns {@code null}. The {@link DependencyResolver} is defensive anyway: a
 * {@code null} result or an exception counts as not eligible with reason {@link BlockReason.Code#ELIGIBILITY_UNAVAILABLE}
 * (logged, nothing of the tenant echoed).
 */
public interface ModuleActivationEligibility {

    /** Whether the tenant ({@code kv_xxxxxx}) may activate {@code module}; never {@code null}. */
    Eligibility check(String tenantSchema, ModuleManifest module);

    /**
     * Outcome of an eligibility check.
     *
     * @param eligible      whether the module may be activated
     * @param reason        why it may not ({@link BlockReason.Code#NOT_ENTITLED},
     *                      {@link BlockReason.Code#ELIGIBILITY_UNAVAILABLE}); {@code null} when eligible
     * @param initialStatus the {@code tenant_modules} status an activation would write ({@code ACTIVE}, later
     *                      {@code TRIAL}); {@code null} when not eligible
     */
    record Eligibility(boolean eligible, BlockReason.Code reason, TenantModuleStatus initialStatus) {

        public Eligibility {
            if (eligible) {
                Objects.requireNonNull(initialStatus, "initialStatus");
                if (reason != null) {
                    throw new IllegalArgumentException("An eligible outcome has no reason");
                }
            } else {
                Objects.requireNonNull(reason, "reason");
                if (initialStatus != null) {
                    throw new IllegalArgumentException("A refused outcome has no initial status");
                }
            }
        }

        /** Eligible, activated with {@code initialStatus}. */
        public static Eligibility eligible(TenantModuleStatus initialStatus) {
            return new Eligibility(true, null, initialStatus);
        }

        /** Not eligible, for {@code reason}. */
        public static Eligibility refused(BlockReason.Code reason) {
            return new Eligibility(false, reason, null);
        }
    }
}
