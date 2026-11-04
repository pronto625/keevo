-- V43 — Story 19.7b (fusion Keevo × Bref, epic 19, ADR-03/04/05/06): the merged offer as starting data.
--
--   plans                 FREE « Gratuit », PRO « Pro », TEAM « Team », BUSINESS « Business » (the Bref grid) and
--                         BUSINESS_TRIAL « Essai Business » (kind TRIAL, mirrors BUSINESS): the registration trial.
--                         PREMIUM_TRIAL and PREMIUM are archived and hidden (codes are never deleted: running
--                         PREMIUM_TRIAL trials go to term, PREMIUM subscriptions are moved to TEAM by the one-shot
--                         PlanMigrationJob — module scripts may not update data, so the move is a boot job).
--   plan_profiles         + max_employees_per_store (NULL = unlimited): Bref's member limit is per shop.
--                         FREE 1 store / 1 employee / 1 per store / 500 products; PRO 3 / ∞ / 3 / ∞; TEAM 8 / ∞ / 8 / ∞;
--                         BUSINESS and BUSINESS_TRIAL unlimited. Tenants above a limit keep their records (ADR-03).
--   profile_modules       PRO: invoicing, exports, report-config (every other paid module explicitly withheld, or the
--                         PAID kind would grant it); TEAM, BUSINESS, BUSINESS_TRIAL: the 8 paid modules.
--   plan_settings         + default_paid_plan_code (the plan « Activer Premium » grants by default) = TEAM; the
--                         trial becomes BUSINESS_TRIAL (1 month when still on the V42 length of 6).
--   tenant_plan_events    cause + PLAN_MIGRATION (the PREMIUM → TEAM move).
--   data_migrations       done-markers of one-shot boot data migrations (the job runs once, not on every boot).
--   plan_settings trial   moves off PREMIUM_TRIAL whatever its length (archived plan); 1 month only if still 6.
--
-- Idempotent; public schema only; data touched with guards only (an edited value is never overwritten).

-- ── plans ─────────────────────────────────────────────────────────────────────────────────────────────────────
INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES
    ('FREE', 'Gratuit', 0, true, 'FREE'),
    ('PRO', 'Pro', 10, true, 'PAID'),
    ('TEAM', 'Team', 20, true, 'PAID'),
    ('BUSINESS', 'Business', 30, true, 'PAID'),
    ('BUSINESS_TRIAL', 'Essai Business', 40, true, 'TRIAL')
ON CONFLICT (code) DO NOTHING;

-- FREE: its V42 seeded label already is « Gratuit » (an edited label is kept: ON CONFLICT DO NOTHING above); its
-- order becomes 0 only while it still holds the V42 seed
UPDATE public.plans SET sort_order = 0, updated_at = now() WHERE code = 'FREE' AND sort_order = 1;

-- ── plan_profiles: per-store employee limit ───────────────────────────────────────────────────────────────────
ALTER TABLE public.plan_profiles ADD COLUMN IF NOT EXISTS max_employees_per_store INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'plan_profiles_max_employees_per_store_check'
                   AND conrelid = 'public.plan_profiles'::regclass) THEN
        ALTER TABLE public.plan_profiles ADD CONSTRAINT plan_profiles_max_employees_per_store_check
            CHECK (max_employees_per_store IS NULL OR max_employees_per_store > 0);
    END IF;
END $$;

INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, max_employees, max_employees_per_store) VALUES
    ('FREE', 1, 500, 1, 1),
    ('PRO', 3, NULL, NULL, 3),
    ('TEAM', 8, NULL, NULL, 8),
    ('BUSINESS', NULL, NULL, NULL, NULL),
    ('BUSINESS_TRIAL', NULL, NULL, NULL, NULL)
ON CONFLICT (plan_type) DO NOTHING;

-- FREE seeded by V33 (1 store / 500 products / 2 employees): 1 employee, 1 per store — only while still on V33 values
UPDATE public.plan_profiles SET max_employees = 1, max_employees_per_store = 1, updated_at = now()
WHERE plan_type = 'FREE' AND max_employees = 2 AND max_employees_per_store IS NULL;

-- ── profile_modules (existing registry only) ──────────────────────────────────────────────────────────────────
INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT 'PRO', m.module_id, m.module_id IN ('invoicing', 'exports', 'report-config')
FROM (VALUES ('advanced-reports'), ('alerts'), ('counting'), ('exports'), ('invoicing'), ('multi-store'),
             ('profitability'), ('report-config')) AS m (module_id)
ON CONFLICT (plan_type, module_id) DO NOTHING;

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, m.module_id, true
FROM (VALUES ('TEAM'), ('BUSINESS'), ('BUSINESS_TRIAL')) AS p (plan_type)
CROSS JOIN (VALUES ('advanced-reports'), ('alerts'), ('counting'), ('exports'), ('invoicing'), ('multi-store'),
                   ('profitability'), ('report-config')) AS m (module_id)
ON CONFLICT (plan_type, module_id) DO NOTHING;

-- ── plan_settings ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.plan_settings ADD COLUMN IF NOT EXISTS default_paid_plan_code VARCHAR(30);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_plan_settings_default_paid_plan'
                   AND conrelid = 'public.plan_settings'::regclass) THEN
        ALTER TABLE public.plan_settings ADD CONSTRAINT fk_plan_settings_default_paid_plan
            FOREIGN KEY (default_paid_plan_code) REFERENCES public.plans (code);
    END IF;
END $$;

UPDATE public.plan_settings SET default_paid_plan_code = 'TEAM', updated_at = now()
WHERE default_paid_plan_code IS NULL;
ALTER TABLE public.plan_settings ALTER COLUMN default_paid_plan_code SET NOT NULL;

-- the trial leaves the (archived) PREMIUM_TRIAL whatever its length; the length becomes 1 month only if still 6
UPDATE public.plan_settings SET trial_plan_code = 'BUSINESS_TRIAL',
    trial_months = CASE WHEN trial_months = 6 THEN 1 ELSE trial_months END, updated_at = now()
WHERE trial_plan_code = 'PREMIUM_TRIAL';

-- ── Keevo plans archived (kept for their tenants, no longer offered) ──────────────────────────────────────────
UPDATE public.plans SET sort_order = 90, visible = false, archived_at = now(), updated_at = now()
WHERE code = 'PREMIUM_TRIAL' AND archived_at IS NULL;
UPDATE public.plans SET sort_order = 91, visible = false, archived_at = now(), updated_at = now()
WHERE code = 'PREMIUM' AND archived_at IS NULL;

-- ── plan event cause: + PLAN_MIGRATION ────────────────────────────────────────────────────────────────────────
-- added NOT VALID (no full scan under the exclusive lock), then validated (a weaker lock)
ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_cause_check;
ALTER TABLE public.tenant_plan_events ADD CONSTRAINT tenant_plan_events_cause_check
    CHECK (cause IN ('ADMIN_ACTIVATION', 'ADMIN_DOWNGRADE', 'EXPIRY', 'REGISTRATION', 'REALIGNMENT',
                     'PLAN_MIGRATION')) NOT VALID;
ALTER TABLE public.tenant_plan_events VALIDATE CONSTRAINT tenant_plan_events_cause_check;

-- ── one-shot data migrations: done-markers (PlanMigrationJob records 'plan-migration-premium-team') ─────────────
CREATE TABLE IF NOT EXISTS public.data_migrations (
    name    VARCHAR(100) PRIMARY KEY,
    done_at TIMESTAMPTZ  NOT NULL
);
