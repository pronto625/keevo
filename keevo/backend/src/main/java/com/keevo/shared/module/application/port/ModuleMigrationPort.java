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
    default Outcome migrate(String tenantSchema, String moduleId) {
        return migrate(tenantSchema, moduleId, null);
    }

    /**
     * Same, adopting an existing schema (Story 13.9): when the module's history table does not exist yet and the
     * baseline's probe table exists in the schema, the history is created with a baseline at the baseline's version and
     * the scripts up to it are not executed.
     *
     * @param baseline the module's existing-schema baseline, {@code null} for none
     */
    Outcome migrate(String tenantSchema, String moduleId, com.keevo.shared.module.ExistingSchemaBaseline baseline);

    /**
     * Story 14.1 — the highest versioned script the module ships ({@code V<version>__…sql}), {@code null} when none
     * (or unknown to this implementation: the caller then provisions to find out).
     */
    default String latestVersion(String moduleId) {
        return null;
    }

    /** Story 14.1 — whether the tenant schema exists (a tenant row whose schema is not created yet: nothing to do). */
    default boolean schemaExists(String tenantSchema) {
        return true;
    }

    /**
     * Story 14.1 — the highest version applied in the tenant schema (a {@code 0} baseline row does not count; an
     * adopted existing schema's baseline does), {@code null} when the module's history table does not exist or holds
     * nothing. One cheap read, no migration.
     */
    default String currentVersion(String tenantSchema, String moduleId) {
        return null;
    }

    /**
     * @param fromVersion highest applied version before the run ({@code null}: none)
     * @param toVersion   highest applied version after the run ({@code null}: none)
     * @param applied     migrations applied by the run
     */
    record Outcome(String fromVersion, String toVersion, int applied) {
    }
}
