package com.keevo.shared.module;

import java.util.Arrays;
import java.util.Optional;

/**
 * Status of a module for a tenant ({@code public.tenant_modules.status}, spec-socle §2.2). Only {@link #TRIAL} and
 * {@link #ACTIVE} make a module active.
 */
public enum TenantModuleStatus {
    AVAILABLE, TRIAL, ACTIVE, EXPIRED, DISABLED;

    /** Whether a module in this status is active for the tenant. */
    public boolean isActive() {
        return this == TRIAL || this == ACTIVE;
    }

    /** The status stored as {@code code}, if it is one (the column CHECK guarantees it). */
    public static Optional<TenantModuleStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.name().equals(code)).findFirst();
    }
}
