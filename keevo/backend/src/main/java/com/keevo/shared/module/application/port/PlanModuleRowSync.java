package com.keevo.shared.module.application.port;

import java.util.UUID;

/**
 * Story 16.5 — keeps the paid-module {@code tenant_modules} rows of one tenant equivalent to its plan
 * ({@code origin = 'PLAN'} rows, see {@code PlanModuleRows}). Called by every subscription writer (plan activation,
 * plan change, nightly expiry, signup) <b>inside its own transaction, after the subscription update</b>: the plan is
 * read on that transaction, so a rollback of the plan change rolls the rows back too. Rows of origin {@code ADMIN}
 * (super-admin lifecycle) or {@code SYSTEM} are never modified; a {@code PLAN} row is rewritten only when it differs
 * (a second call writes nothing). The module registry request cache of the tenant is evicted after commit.
 */
public interface PlanModuleRowSync {

    /**
     * Writes the rows the plan calls for (joins the caller's transaction, opens one otherwise).
     *
     * @param tenantId     the tenant's {@code public.tenants.id}
     * @param tenantSchema its schema ({@code kv_xxxxxx})
     * @throws PlanUnreadableException the plan could not be read: nothing written
     */
    SyncResult sync(UUID tenantId, String tenantSchema);

    /** What {@link #sync} would write, without writing anything (backfill dry run). */
    SyncResult preview(UUID tenantId, String tenantSchema);

    /**
     * @param inserted  rows created
     * @param updated   {@code PLAN} rows rewritten
     * @param unchanged {@code PLAN} rows already equal to the plan
     * @param preserved rows left as they are because a super-admin ({@code ADMIN}) or the platform ({@code SYSTEM})
     *                  set them
     */
    record SyncResult(int inserted, int updated, int unchanged, int preserved) {

        public static final SyncResult NONE = new SyncResult(0, 0, 0, 0);

        /** Rows written (or that would be). */
        public int written() {
            return inserted + updated;
        }
    }

    /** The tenant's plan could not be read (database fault, timeout): no row is derived. */
    class PlanUnreadableException extends RuntimeException {

        public PlanUnreadableException() {
            super("The tenant's plan could not be read; module rows left unchanged");
        }
    }
}
