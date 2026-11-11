-- V81 — Story 24.2 (fusion Keevo × Bref, epic 24, FUS-MIG1/MIG2): the Bref migration tool (platform module
-- « migration », SUPER_ADMIN).
--
--   migration_batches   one run of the import of one Bref owner (source, status, who, when, counts, blocking cases,
--                       notes, error label). One RUNNING run per owner at a time (partial unique index).
--   migration_records   one line per row written by the import: target (Keevo table, id) ← source (Bref table, id),
--                       sha256 checksum of the source row, optional note (an anomaly kept as is, a skipped logo…).
--                       Unique per (source table, source id, target table): a re-run finds what it already wrote, and
--                       several sources may point to one target (product merge, 24.3). Indexed by target (audit,
--                       claims) and by owner and target table (what a run reads; rollback purge, 24.9).
--   tenant_plan_events  cause MIGRATION_BREF: the first plan event of an imported tenant (and, in 24.5, the plan in
--                       force at the migration date) — a data migration, not a commercial change.
--   tenant_module_activation  « migration » owner-disabled for the existing tenants (db/modules/README.md « Modules
--                       introduced after the go-live »; a platform module never shows in « Mes modules » anyway).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING); set-based.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.migration_batches (
    id             UUID         PRIMARY KEY,
    bref_owner_id  UUID         NOT NULL,
    source         VARCHAR(20)  NOT NULL CONSTRAINT migration_batches_source_check
                                CHECK (source IN ('EXPORT', 'DATABASE')),
    status         VARCHAR(20)  NOT NULL CONSTRAINT migration_batches_status_check
                                CHECK (status IN ('RUNNING', 'COMPLETED', 'BLOCKED', 'FAILED')),
    tenant_id      UUID,
    started_by     UUID,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    counts         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    cases          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    notes          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    error_label    VARCHAR(300)
);

-- one running import per owner (two concurrent runs of the same owner: the second is refused); the batch lists
-- (owner, newest first) and their indexes come with their readers in 24.9
CREATE UNIQUE INDEX IF NOT EXISTS uq_migration_batches_running
    ON public.migration_batches (bref_owner_id) WHERE status = 'RUNNING';

CREATE TABLE IF NOT EXISTS public.migration_records (
    id             BIGSERIAL    PRIMARY KEY,
    batch_id       UUID         NOT NULL REFERENCES public.migration_batches (id),
    bref_owner_id  UUID         NOT NULL,
    target_table   VARCHAR(63)  NOT NULL,
    target_id      UUID         NOT NULL,
    source_table   VARCHAR(63)  NOT NULL,
    source_id      UUID         NOT NULL,
    checksum       VARCHAR(64)  NOT NULL CONSTRAINT migration_records_checksum_check
                                CHECK (checksum ~ '^[0-9a-f]{64}$'),
    note           VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_migration_records_source
    ON public.migration_records (source_table, source_id, target_table);
CREATE INDEX IF NOT EXISTS idx_migration_records_target
    ON public.migration_records (target_table, target_id);
-- a run reads the owner's records of the tables its steps write; rollback (24.9) purges by owner (prefix)
CREATE INDEX IF NOT EXISTS idx_migration_records_owner
    ON public.migration_records (bref_owner_id, target_table);
CREATE INDEX IF NOT EXISTS idx_migration_records_batch
    ON public.migration_records (batch_id);

ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_cause_check;
ALTER TABLE public.tenant_plan_events ADD CONSTRAINT tenant_plan_events_cause_check CHECK (cause IN (
    'ADMIN_ACTIVATION', 'ADMIN_DOWNGRADE', 'EXPIRY', 'REGISTRATION', 'REALIGNMENT', 'PLAN_MIGRATION',
    'SELF_SERVICE_PAYMENT', 'MIGRATION_BREF'));

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'migration', false, now() FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
