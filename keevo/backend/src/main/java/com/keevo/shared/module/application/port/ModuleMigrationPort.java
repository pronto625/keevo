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
     * Story 16.12 — {@link #currentVersion} of several modules in one schema (module id → version; a module without
     * history or without a version is absent). The JDBC implementation reads them in two queries.
     */
    default java.util.Map<String, String> schemaVersions(String tenantSchema, java.util.Collection<String> moduleIds) {
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (String moduleId : moduleIds) {
            String version = currentVersion(tenantSchema, moduleId);
            if (version != null) {
                versions.put(moduleId, version);
            }
        }
        return versions;
    }

    /**
     * Story 16.12 — {@link #currentVersion} of one module in several schemas (schema → version; a schema without the
     * module's history or without a version is absent): set-based detection of tenants behind, in two queries for
     * the JDBC implementation (never one probe per tenant).
     */
    default java.util.Map<String, String> historyVersions(String moduleId, java.util.Collection<String> tenantSchemas) {
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (String schema : tenantSchemas) {
            String version = currentVersion(schema, moduleId);
            if (version != null) {
                versions.put(schema, version);
            }
        }
        return versions;
    }

    /**
     * @param fromVersion highest applied version before the run ({@code null}: none)
     * @param toVersion   highest applied version after the run ({@code null}: none)
     * @param applied     migrations applied by the run
     */
    record Outcome(String fromVersion, String toVersion, int applied) {
    }
}
