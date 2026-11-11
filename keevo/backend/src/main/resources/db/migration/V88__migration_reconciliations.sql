-- V88 — Story 24.8 (fusion Keevo × Bref, epic 24, FUS-MIG3, ADR-10): the reconciliation of an imported Bref owner.
--
--   migration_reconciliations   one run of the reconciliation of one import batch: status RECONCILED or
--                               RECONCILIATION_FAILED (an out-of-tolerance gap, or a reconciliation that could not run),
--                               gap / explained line counts, and the report (JSONB: aggregates and French labels only,
--                               no personal data). History kept (one row per run); the cutover's activation (24.9)
--                               reads the latest run of each owner's latest batch (any status: a newer blocked,
--                               failed or running import blocks the owner until it completes and is reconciled).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS). Deleting a batch (rollback, 24.9) deletes its runs.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.migration_reconciliations (
    id             UUID          PRIMARY KEY,
    batch_id       UUID          NOT NULL REFERENCES public.migration_batches (id) ON DELETE CASCADE,
    bref_owner_id  UUID          NOT NULL,
    tenant_id      UUID          NOT NULL,
    status         VARCHAR(30)   NOT NULL CONSTRAINT migration_reconciliations_status_check
                                 CHECK (status IN ('RECONCILED', 'RECONCILIATION_FAILED')),
    gaps           INTEGER       NOT NULL DEFAULT 0,
    explained      INTEGER       NOT NULL DEFAULT 0,
    report         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    error_label    VARCHAR(300),
    started_by     UUID,
    started_at     TIMESTAMPTZ   NOT NULL,
    finished_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- the latest run of an owner (dashboard, activation gate: DISTINCT ON (bref_owner_id))
CREATE INDEX IF NOT EXISTS idx_migration_reconciliations_owner
    ON public.migration_reconciliations (bref_owner_id, finished_at DESC);
-- the latest run of a batch (report endpoint, gate join); also the FK's cascade path
CREATE INDEX IF NOT EXISTS idx_migration_reconciliations_batch
    ON public.migration_reconciliations (batch_id, finished_at DESC);
-- the gate: each owner's latest batch (DISTINCT ON (bref_owner_id) … ORDER BY started_at DESC)
CREATE INDEX IF NOT EXISTS idx_migration_batches_owner_started
    ON public.migration_batches (bref_owner_id, started_at DESC);
