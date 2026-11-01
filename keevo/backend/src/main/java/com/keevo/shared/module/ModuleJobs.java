package com.keevo.shared.module;

import java.util.UUID;

/**
 * Story 16.4 — starts the long module operations of the super-admin console as background jobs
 * ({@link com.keevo.shared.job.AdminJobs}): the {@code tenant_modules} backfill (16.5) and the provisioning of one
 * module for every live tenant. Each call returns the job id at once; the console polls the job for its progress
 * and report (same shapes as {@link ModuleBackfill.BackfillReport} / {@link ProvisioningReport}).
 */
public interface ModuleJobs {

    /**
     * @throws com.keevo.shared.domain.exception.DomainException {@code ADMIN_JOB_RUNNING} (409) when a backfill job is
     *         already queued or running
     */
    UUID startBackfill(boolean dryRun, UUID actorId);

    /**
     * Provisions the module's tables for every live tenant for which it is active; tenants already at the module's
     * latest version are skipped without any Flyway run.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MODULE_NOT_FOUND} (404) for an unknown or
     *         platform module, {@code VALIDATION_ERROR} (422) for a module without tables, {@code ADMIN_JOB_RUNNING}
     *         (409) when a provisioning job is already queued or running
     */
    default UUID startProvisionAll(String moduleId, UUID actorId) {
        return startProvisionAll(moduleId, false, actorId);
    }

    /** @param force run Flyway for every tenant, even those already at the latest version (full validate) */
    UUID startProvisionAll(String moduleId, boolean force, UUID actorId);

    /** Whether the module ships tables (migrations) that {@link #startProvisionAll} can create. */
    boolean hasMigrations(String moduleId);
}
