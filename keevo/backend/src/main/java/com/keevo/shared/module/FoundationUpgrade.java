package com.keevo.shared.module;

/**
 * Story 16.12 — outcome of one boot-time upgrade of the foundation tables over every live tenant
 * ({@link ModuleProvisioner#upgradeFoundation(int)}).
 *
 * @param checked     live tenants whose schema exists (the platform tenant excluded)
 * @param provisioned tenants found behind and brought to the latest foundation versions
 * @param failed      tenants found behind whose provisioning failed (retried at the next start or request)
 * @param durationMs  wall-clock duration of the whole upgrade
 */
public record FoundationUpgrade(int checked, int provisioned, int failed, long durationMs) {
}
