package com.keevo.shared.module;

/**
 * A module could not be provisioned inside a tenant schema (Story 13.4): a migration failed, or the
 * {@code provisioned_version} write failed. The message carries the module id, the schema length and a short
 * failure code (Flyway error code, SQL state or exception class) — never the schema value nor SQL text
 * (Story 12.12 D5). The underlying exception is deliberately <b>not</b> attached as the cause (its message holds SQL
 * and the schema name). Story 19.11: the root cause is kept as a short text ({@link #rootCause()}: exception class and
 * message of the innermost cause, truncated) for the boot-upgrade log only, never in the exception message.
 */
public class ModuleProvisioningException extends RuntimeException {

    private final String moduleId;
    private final String failure;
    private final String highestAppliedVersion;
    private final String rootCause;

    /**
     * @param moduleId              the module (a validated catalogue id)
     * @param tenantSchema          the schema (only its length is reported)
     * @param failure               a short technical code without SQL text (e.g. {@code ERROR/42601})
     * @param highestAppliedVersion highest migration version applied when it failed ({@code null}: none)
     */
    public ModuleProvisioningException(String moduleId, String tenantSchema, String failure,
                                       String highestAppliedVersion) {
        this(moduleId, tenantSchema, failure, highestAppliedVersion, null);
    }

    /**
     * Story 19.11 — same, keeping {@code rootCause} (see {@link #rootCause()}).
     */
    public ModuleProvisioningException(String moduleId, String tenantSchema, String failure,
                                       String highestAppliedVersion, Throwable cause) {
        super("Provisioning of module '" + moduleId + "' failed (schema length="
                + (tenantSchema == null ? "null" : String.valueOf(tenantSchema.length())) + ", failure=" + failure + ")");
        this.moduleId = moduleId;
        this.failure = failure;
        this.highestAppliedVersion = highestAppliedVersion;
        this.rootCause = describeRoot(cause);
    }

    /** Max length of {@link #rootCause()}. */
    static final int ROOT_CAUSE_MAX = 300;

    /**
     * Story 19.11 — {@code SimpleClassName: first line of the message} of the innermost cause, plus
     * {@code [SQLState x]} for a {@link java.sql.SQLException}; at most {@value #ROOT_CAUSE_MAX} characters;
     * {@code null} when no cause was given. Only the first line is kept: PostgreSQL {@code Detail:} / {@code Where:}
     * lines can carry row values. For operator logs only: it may name the schema.
     */
    public String rootCause() {
        return rootCause;
    }

    /** Max causes walked (a cause cycle never loops forever). */
    static final int MAX_CAUSE_DEPTH = 32;

    public static String describeRoot(Throwable cause) {
        if (cause == null) {
            return null;
        }
        Throwable root = cause;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().strip();
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline).strip();
        }
        String text = root.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
        if (root instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
            text += " [SQLState " + sql.getSQLState() + "]";
        }
        return text.length() <= ROOT_CAUSE_MAX ? text : text.substring(0, ROOT_CAUSE_MAX) + "…";
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
