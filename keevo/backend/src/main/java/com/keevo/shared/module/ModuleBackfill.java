package com.keevo.shared.module;

import java.util.List;

/**
 * Story 16.5 — on-demand, super-admin backfill of {@code public.tenant_modules} for every live tenant (MOD-FR19):
 * the paid-module rows equivalent to each tenant's plan ({@code origin = 'PLAN'}, the remaining trial kept exactly),
 * then the tables of every module that is enabled for the tenant and ships migrations ({@code crm} baseline
 * adoption, {@code invoicing}…). Idempotent (a second run writes nothing), never run at boot, one run at a time
 * across instances. The effective module states are the same before and after.
 */
public interface ModuleBackfill {

    /**
     * @param dryRun {@code true}: report what would be written / provisioned, write nothing
     * @throws com.keevo.shared.domain.exception.DomainException {@code MODULE_BACKFILL_RUNNING} (409) when another
     *         run holds the lock
     */
    default BackfillReport run(boolean dryRun) {
        return run(dryRun, com.keevo.shared.job.ProgressListener.NONE);
    }

    /**
     * Story 16.4 — same, reporting the progress tenant by tenant (the console runs it as a background job).
     *
     * @param progress told {@code (tenants done, live tenants)} after each tenant
     */
    BackfillReport run(boolean dryRun, com.keevo.shared.job.ProgressListener progress);

    /**
     * Outcome of a run. Tenants are named by their public code ({@code KV-XXXXXX}) only.
     *
     * @param dryRun             whether nothing was written
     * @param tenantsProcessed   tenants whose rows were synced (or previewed)
     * @param tenantsSkipped     tenants without a schema yet, and the platform tenant
     * @param rowsInserted       rows created (or that would be)
     * @param rowsUpdated        plan-derived rows rewritten (or that would be)
     * @param rowsUnchanged      plan-derived rows already equal to the plan
     * @param rowsPreserved      rows left as set by a super-admin or by the platform
     * @param modulesProvisioned module tables provisioned (or that would be), across tenants
     * @param tenants            the tenants with something written or provisioned
     * @param failures           tenants for which something failed (the run went on)
     */
    record BackfillReport(boolean dryRun, int tenantsProcessed, int tenantsSkipped, int rowsInserted,
                          int rowsUpdated, int rowsUnchanged, int rowsPreserved, int modulesProvisioned,
                          List<TenantChange> tenants, List<Failure> failures) {

        public BackfillReport {
            tenants = List.copyOf(tenants);
            failures = List.copyOf(failures);
        }

        /** Rows written (or that would be). */
        public int rowsWritten() {
            return rowsInserted + rowsUpdated;
        }
    }

    /**
     * @param tenantCode  public code
     * @param inserted    rows created
     * @param updated     rows rewritten
     * @param provisioned modules whose tables were provisioned (display names)
     */
    record TenantChange(String tenantCode, int inserted, int updated, List<String> provisioned) {

        public TenantChange {
            provisioned = List.copyOf(provisioned);
        }
    }

    /**
     * @param tenantCode public code
     * @param reason     French message, no SQL, schema name nor identifier
     */
    record Failure(String tenantCode, String reason) {
    }
}
