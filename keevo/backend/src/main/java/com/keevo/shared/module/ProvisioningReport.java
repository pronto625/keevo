package com.keevo.shared.module;

import java.util.List;

/**
 * Outcome of {@link ModuleProvisioner#provisionAll(String)}: tenants are named by their public code
 * ({@code KV-XXXXXX}), never by schema name.
 *
 * @param moduleId    the module
 * @param provisioned tenants whose schema is up to date after the run (whether or not migrations were applied)
 * @param skipped     tenants without a schema yet, or for which the module is not active (nothing done)
 * @param failures    tenants whose provisioning failed
 */
public record ProvisioningReport(String moduleId, List<String> provisioned, List<String> skipped,
                                 List<Failure> failures) {

    public ProvisioningReport {
        provisioned = List.copyOf(provisioned);
        skipped = List.copyOf(skipped);
        failures = List.copyOf(failures);
    }

    /** Whether every live tenant was provisioned or skipped. */
    public boolean successful() {
        return failures.isEmpty();
    }

    /**
     * One failed tenant.
     *
     * @param tenantCode the tenant's public code
     * @param reason     a message without SQL text nor schema name
     */
    public record Failure(String tenantCode, String reason) {
    }
}
