package com.keevo.shared.module;

/**
 * Outcome of {@link ModuleProvisioner#provision(String, String)} for one (tenant schema, module).
 *
 * @param moduleId          the module
 * @param fromVersion       highest applied migration version before the run ({@code null}: none)
 * @param toVersion         highest applied migration version after the run ({@code null}: none, e.g. no migrations)
 * @param migrationsApplied number of migrations applied by this run (0 = already up to date / no migrations)
 */
public record ProvisioningResult(String moduleId, String fromVersion, String toVersion, int migrationsApplied) {

    public ProvisioningResult {
        if (migrationsApplied < 0) {
            throw new IllegalArgumentException("migrationsApplied must be >= 0");
        }
    }

    /** Whether this run changed the schema. */
    public boolean changed() {
        return migrationsApplied > 0;
    }
}
