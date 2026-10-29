package com.keevo.shared.module;

/**
 * Effective state of a module for a tenant, evaluated in real time from {@code public.tenant_modules} and the
 * injected {@code Clock} (Story 13.5, spec-socle §2.2, §9.1). Computed in one place
 * ({@code shared.module.application.ModuleStateRules}) and shared by {@link ModuleRegistry} and
 * {@link EntitlementService}.
 */
public enum ModuleState {
    /** Subscribed (TRIAL/ACTIVE not yet expired, free module without a row, or {@code core}): read and write. */
    ENABLED,
    /** Subscription expired, still in its grace period ({@code grace_until} in the future): reads only. */
    READ_ONLY,
    /** Not installed, not subscribed, disabled, or grace over: no access (data is kept). */
    DISABLED;

    /** Whether the module's data can be read (anything but {@link #DISABLED}). */
    public boolean readable() {
        return this != DISABLED;
    }

    /** Whether the module accepts writes (only {@link #ENABLED}). */
    public boolean writable() {
        return this == ENABLED;
    }
}
