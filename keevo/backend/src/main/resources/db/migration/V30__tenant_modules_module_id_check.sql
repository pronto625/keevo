-- V30 — Story 13.2 review: format guard on public.tenant_modules.module_id.
--
-- The registry matches tenant_modules rows to installed manifests by exact id. A row such as 'POS' or 'pos '
-- would match nothing and the module would fall back to its manifest `free` flag (fail open). The CHECK pins the
-- manifest id syntax (ModuleManifestLoader.ID_PATTERN, ≤ 50 chars via the column type). Idempotent (V13 style):
-- drop by name, re-add; existing violating rows (none expected — the table is not populated yet) → NOT VALID +
-- WARNING so a legacy row never blocks deploy. Public schema only.
DO $$
BEGIN
    IF to_regclass('public.tenant_modules') IS NULL THEN
        RETURN;
    END IF;
    ALTER TABLE public.tenant_modules DROP CONSTRAINT IF EXISTS tenant_modules_module_id_check;
    BEGIN
        ALTER TABLE public.tenant_modules ADD CONSTRAINT tenant_modules_module_id_check
            CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');
    EXCEPTION WHEN check_violation THEN
        ALTER TABLE public.tenant_modules ADD CONSTRAINT tenant_modules_module_id_check
            CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$') NOT VALID;
        RAISE WARNING 'V30: public.tenant_modules has rows violating tenant_modules_module_id_check; constraint added NOT VALID';
    END;
END $$;
