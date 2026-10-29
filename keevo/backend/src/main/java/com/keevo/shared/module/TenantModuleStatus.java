package com.keevo.shared.module;

import java.util.Arrays;
import java.util.Optional;

/**
 * Status of a module for a tenant ({@code public.tenant_modules.status}, spec-socle §2.2). The stored status alone
 * does not decide access: since Story 13.5 the effective {@link ModuleState} also depends on {@code expires_at} and
 * {@code grace_until}, evaluated in real time ({@code shared.module.application.ModuleStateRules}).
 */
public enum TenantModuleStatus {
    AVAILABLE, TRIAL, ACTIVE, EXPIRED, DISABLED;

    /** The status stored as {@code code}, if it is one (the column CHECK guarantees it). */
    public static Optional<TenantModuleStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.name().equals(code)).findFirst();
    }
}
