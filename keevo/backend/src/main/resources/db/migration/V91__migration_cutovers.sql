-- V91 — Story 24.9 (fusion Keevo × Bref, epic 24, ADR-10, FUS-MIG1/MIG3/MIG5/MIG10, FUS-NFR10): the one-shot cutover
-- command of the Bref migration tool.
--
--   migration_cutovers        one cutover (or rehearsal) over a restored Bref database: status, who, when, parallelism,
--                             heartbeat of the running coordinator, the « go » (actor, time, accepted owners with their
--                             reason — the signed exclusion list), the rollback, and the archived report (JSONB:
--                             aggregates, durations and French labels only). One open cutover at a time (partial
--                             unique index); ACTIVATED and ROLLED_BACK cutovers stay as history.
--   migration_cutover_owners  one line per Bref owner listed by the cutover: listing position (the report names owners
--                             « Compte n »), status, latest batch and tenant (handles, no foreign key: a rollback
--                             purges the batches and the tenants, the line stays as history), French reason, import and
--                             reconciliation durations, figures (counts and sums Bref / Keevo from the reconciliation).
--   idx_migration_batches_started  the super-admin list of import batches, newest first (keyset).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS).
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.migration_cutovers (
    id               UUID          PRIMARY KEY,
    source           VARCHAR(20)   NOT NULL CONSTRAINT migration_cutovers_source_check CHECK (source IN ('DATABASE')),
    status           VARCHAR(20)   NOT NULL CONSTRAINT migration_cutovers_status_check
                                   CHECK (status IN ('RUNNING', 'READY', 'FAILED', 'ACTIVATED', 'ROLLING_BACK',
                                                     'ROLLED_BACK')),
    parallelism      INTEGER       NOT NULL CONSTRAINT migration_cutovers_parallelism_check
                                   CHECK (parallelism BETWEEN 1 AND 8),
    runs             INTEGER       NOT NULL DEFAULT 1,
    started_by       UUID,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    heartbeat_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    activated_by     UUID,
    activated_at     TIMESTAMPTZ,
    accepted_owners  JSONB         NOT NULL DEFAULT '[]'::jsonb,
    rolled_back_by   UUID,
    rolled_back_at   TIMESTAMPTZ,
    error_label      VARCHAR(300),
    report           JSONB         NOT NULL DEFAULT '{}'::jsonb
);

-- one open cutover at a time: a second start is refused (« Une bascule est déjà ouverte »)
CREATE UNIQUE INDEX IF NOT EXISTS uq_migration_cutovers_open
    ON public.migration_cutovers ((true)) WHERE status NOT IN ('ACTIVATED', 'ROLLED_BACK');
-- the super-admin list of cutovers, newest first (keyset)
CREATE INDEX IF NOT EXISTS idx_migration_cutovers_started
    ON public.migration_cutovers (started_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS public.migration_cutover_owners (
    cutover_id       UUID          NOT NULL REFERENCES public.migration_cutovers (id) ON DELETE CASCADE,
    bref_owner_id    UUID          NOT NULL,
    position         INTEGER       NOT NULL,
    status           VARCHAR(20)   NOT NULL CONSTRAINT migration_cutover_owners_status_check
                                   CHECK (status IN ('PENDING', 'RECONCILED', 'GAP', 'BLOCKED', 'FAILED',
                                                     'NOT_MIGRATED', 'ACTIVATED', 'ROLLED_BACK')),
    batch_id         UUID,
    tenant_id        UUID,
    reason           VARCHAR(300),
    import_ms        INTEGER,
    reconcile_ms     INTEGER,
    figures          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    PRIMARY KEY (cutover_id, bref_owner_id)
);

-- the owners of a cutover in listing order (keyset), per status (filters, gate input, activation, rollback)
CREATE UNIQUE INDEX IF NOT EXISTS uq_migration_cutover_owners_position
    ON public.migration_cutover_owners (cutover_id, position);
CREATE INDEX IF NOT EXISTS idx_migration_cutover_owners_status
    ON public.migration_cutover_owners (cutover_id, status, position);

-- the super-admin list of import batches, newest first (keyset on (started_at, id)) — routed from 24.2
CREATE INDEX IF NOT EXISTS idx_migration_batches_started
    ON public.migration_batches (started_at DESC, id DESC);
