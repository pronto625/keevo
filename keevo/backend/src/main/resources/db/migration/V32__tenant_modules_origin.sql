-- V32 — Story 16.5: provenance of each public.tenant_modules row.
--
--   SYSTEM  free modules (provisioner ACTIVE/FREE rows, any row the platform writes on its own);
--   PLAN    paid-module rows derived from the tenant's subscription (PlanModuleRowSync, rewritten on every plan
--           change and by the on-demand backfill);
--   ADMIN   rows set by the super-admin module lifecycle (activate / deactivate) — never rewritten from the plan.
--
-- Existing rows are back-labelled: before 16.5 only the super-admin lifecycle wrote paid-module rows (the
-- provisioner never inserts a paid row, Story 14.1), so a row of a paid module (module_definitions.is_free = false)
-- is ADMIN, every other row SYSTEM. Schema change + labels only: no row is created here (the backfill is an
-- on-demand super-admin action, never a boot-time write). Idempotent. Public schema only.
DO $$
BEGIN
    IF to_regclass('public.tenant_modules') IS NULL THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'tenant_modules' AND column_name = 'origin') THEN
        ALTER TABLE public.tenant_modules ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'SYSTEM';
        UPDATE public.tenant_modules tm SET origin = 'ADMIN'
        FROM public.module_definitions md
        WHERE md.id = tm.module_id AND md.is_free = false;
    END IF;
    ALTER TABLE public.tenant_modules DROP CONSTRAINT IF EXISTS tenant_modules_origin_check;
    ALTER TABLE public.tenant_modules ADD CONSTRAINT tenant_modules_origin_check
        CHECK (origin IN ('SYSTEM', 'PLAN', 'ADMIN'));
END $$;
