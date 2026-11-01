-- V35 — Story 16.4: super-admin background jobs (module backfill, provisionAll of one module, plan-profile re-sync).
--
--   admin_jobs  one row per job started from the console: kind, parameters, status, progress (processed / total),
--               the final report (same JSON shapes as the former synchronous answers, tenants named by code only),
--               the actor and a French error message when it failed.
--
-- One job of a kind at a time: the partial unique index refuses a second QUEUED/RUNNING row of the same kind (the
-- API answers 409). Jobs run on one node only; a job left QUEUED/RUNNING by a restart is marked FAILED « Interrompu
-- par un redémarrage » at boot (never resumed). Idempotent. Public schema only.
CREATE TABLE IF NOT EXISTS public.admin_jobs (
    id            UUID         PRIMARY KEY,
    kind          VARCHAR(30)  NOT NULL
                  CONSTRAINT admin_jobs_kind_check
                  CHECK (kind IN ('MODULE_BACKFILL', 'MODULE_PROVISION_ALL', 'PROFILE_RESYNC')),
    params        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(10)  NOT NULL
                  CONSTRAINT admin_jobs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    processed     INTEGER      NOT NULL DEFAULT 0,
    total         INTEGER,
    report        JSONB,
    actor         UUID,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    error_message VARCHAR(500)
);

CREATE UNIQUE INDEX IF NOT EXISTS admin_jobs_one_active_per_kind
    ON public.admin_jobs (kind) WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS admin_jobs_kind_created_idx ON public.admin_jobs (kind, created_at DESC);
