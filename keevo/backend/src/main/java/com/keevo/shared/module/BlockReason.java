package com.keevo.shared.module;

import java.util.Objects;

/**
 * Why an activation plan or a deactivation is blocked (Story 13.3). Machine-readable only: the UI maps the code to
 * a French message (13.8+).
 *
 * @param code     the reason
 * @param moduleId the module concerned (the ineligible step, the core module, or the active dependent)
 */
public record BlockReason(Code code, String moduleId) {

    public BlockReason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(moduleId, "moduleId");
    }

    /** Reason codes. */
    public enum Code {
        /** The tenant is not entitled to activate this module ({@link ModuleActivationEligibility}). */
        NOT_ENTITLED,
        /**
         * The eligibility of this module could not be decided (the check failed or answered nothing, Story 13.5):
         * not eligible, retry later.
         */
        ELIGIBILITY_UNAVAILABLE,
        /** {@code core} can never be deactivated. */
        CORE_MODULE,
        /** An active module requires the module being deactivated. */
        REQUIRED_BY_ACTIVE_MODULE
    }
}
