package com.keevo.shared.module.application.port;

/**
 * Runs a module's own migrations inside a tenant schema (Story 13.4, spec-socle §2.3). Implemented by
 * {@code shared.module.adapter.out.persistence.FlywayModuleMigrations}.
 */
public interface ModuleMigrationPort {

    /** Whether the module ships at least one migration script. */
    boolean hasMigrations(String moduleId);

    /**
     * Applies the module's pending migrations to the schema (isolated history table, idempotent).
     *
     * @param tenantSchema a valid tenant schema
     * @param moduleId     a catalogue module id
     * @return the versions before/after and the number of migrations applied
     * @throws com.keevo.shared.module.ModuleProvisioningException on a migration failure
     */
    Outcome migrate(String tenantSchema, String moduleId);

    /**
     * @param fromVersion highest applied version before the run ({@code null}: none)
     * @param toVersion   highest applied version after the run ({@code null}: none)
     * @param applied     migrations applied by the run
     */
    record Outcome(String fromVersion, String toVersion, int applied) {
    }
}
