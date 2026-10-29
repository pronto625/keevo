package com.keevo.shared.module;

/**
 * A module could not be provisioned inside a tenant schema (Story 13.4): a migration failed, or the
 * {@code provisioned_version} write failed. The message carries the module id, the schema length and a short
 * failure code (Flyway error code, SQL state or exception class) — never the schema value nor SQL text
 * (Story 12.12 D5). The underlying exception is deliberately <b>not</b> attached as the cause (its message holds SQL
 * and the schema name).
 */
public class ModuleProvisioningException extends RuntimeException {

    private final String moduleId;
    private final String failure;
    private final String highestAppliedVersion;

    /**
     * @param moduleId              the module (a validated catalogue id)
     * @param tenantSchema          the schema (only its length is reported)
     * @param failure               a short technical code without SQL text (e.g. {@code ERROR/42601})
     * @param highestAppliedVersion highest migration version applied when it failed ({@code null}: none)
     */
    public ModuleProvisioningException(String moduleId, String tenantSchema, String failure,
                                       String highestAppliedVersion) {
        super("Provisioning of module '" + moduleId + "' failed (schema length="
                + (tenantSchema == null ? "null" : String.valueOf(tenantSchema.length())) + ", failure=" + failure + ")");
        this.moduleId = moduleId;
        this.failure = failure;
        this.highestAppliedVersion = highestAppliedVersion;
    }

    public String moduleId() {
        return moduleId;
    }

    /** Short failure code (Flyway error code and/or SQL state, or exception class name). */
    public String failure() {
        return failure;
    }

    /** Highest migration version applied when the failure happened ({@code null}: none, or unknown). */
    public String highestAppliedVersion() {
        return highestAppliedVersion;
    }
}
