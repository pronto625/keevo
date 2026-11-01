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
 * @param upToDate    Story 16.4 — tenants already at the module's latest version, left without any Flyway run
 *                    (not counted in {@code skipped}; always 0 for a forced run)
 */
public record ProvisioningReport(String moduleId, List<String> provisioned, List<String> skipped,
                                 List<Failure> failures, int upToDate) {

    public ProvisioningReport {
        provisioned = List.copyOf(provisioned);
        skipped = List.copyOf(skipped);
        failures = List.copyOf(failures);
    }

    public ProvisioningReport(String moduleId, List<String> provisioned, List<String> skipped,
                              List<Failure> failures) {
        this(moduleId, provisioned, skipped, failures, 0);
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
