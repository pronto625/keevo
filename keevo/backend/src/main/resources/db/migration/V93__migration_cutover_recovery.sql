-- V93 — Story 24.9, reprise 11/11/2026 (routed by the 24.13 review): recovery of ONE owner after the « go ».
--
--   migration_cutovers.status       + 'RECOVERING': the lock of an ACTIVATED cutover while one owner left out at the
--                                   « go » is imported again from the frozen copy, reconciled and activated alone.
--   uq_migration_cutovers_open      recreated: RECOVERING is a decided cutover (after the « go »), not an open one.
--   migration_cutovers.recovered_owners  the owners recovered after the « go » (handle, actor, time), as accepted_owners.
--
-- Platform data, public schema only. Idempotent.
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.migration_cutovers DROP CONSTRAINT IF EXISTS migration_cutovers_status_check;
ALTER TABLE public.migration_cutovers ADD CONSTRAINT migration_cutovers_status_check
    CHECK (status IN ('RUNNING', 'READY', 'FAILED', 'ACTIVATED', 'ROLLING_BACK', 'ROLLED_BACK', 'RECOVERING'));

DROP INDEX IF EXISTS public.uq_migration_cutovers_open;
CREATE UNIQUE INDEX IF NOT EXISTS uq_migration_cutovers_open
    ON public.migration_cutovers ((true)) WHERE status NOT IN ('ACTIVATED', 'ROLLED_BACK', 'RECOVERING');

ALTER TABLE public.migration_cutovers
    ADD COLUMN IF NOT EXISTS recovered_owners JSONB NOT NULL DEFAULT '[]'::jsonb;
