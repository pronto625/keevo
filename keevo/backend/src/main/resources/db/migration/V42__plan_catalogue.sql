-- V42 — Story 19.7a (fusion Keevo × Bref, epic 19, ADR-05): plans are data. Nothing of the commercial offer is a
-- closed list in the code or in a constraint any more; a plan created as data works everywhere without a deploy.
--
--   plans          the plan catalogue: code, French label, description, display order, visibility, archive date and
--                  KIND ∈ FREE / TRIAL / PAID. Every behaviour that depended on a plan code (free limits, paid modules,
--                  trial expiry, V1 compatibility value, revenue, admin filters) depends on the kind, read here. The
--                  three kinds are the only fixed list: they are concepts, not offer content. Codes are never deleted,
--                  only archived (archived_at): foreign keys below point at them.
--   plan_settings  one row: the plan a tenant falls back to (expiry job, admin downgrade), the plan a new registration
--                  starts on and its length in months (formerly INTERVAL '6 months' in the code).
--
-- Seed = the three current plans with today's French labels (FREE « Gratuit », PREMIUM_TRIAL « Essai Premium »,
-- PREMIUM « Premium ») and today's trial (PREMIUM_TRIAL, 6 months): behaviour is unchanged. 19.7b seeds the merged
-- offer (FREE/PRO/TEAM/BUSINESS); 21.10 adds the super-admin CRUD.
--
-- The CHECKs on plan codes (plan_profiles V33, tenant_plan_events V17) are replaced by foreign keys to the catalogue,
-- and public.tenants.plan_type gets one too. Plan code columns are widened to VARCHAR(30) (the catalogue's code
-- length; binary-coercible, no rewrite). Foreign keys are added NOT VALID and validated in a guarded block: a legacy
-- row with an unknown code never aborts the migration (new rows are checked either way). Idempotent; public schema
-- only — the tenant subscriptions CHECK is dropped by the core module migration V2 (db/modules/core).

CREATE TABLE IF NOT EXISTS public.plans (
    code        VARCHAR(30)  PRIMARY KEY
                CONSTRAINT plans_code_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    label       VARCHAR(80)  NOT NULL,
    description TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    visible     BOOLEAN      NOT NULL DEFAULT true,
    kind        VARCHAR(10)  NOT NULL
                CONSTRAINT plans_kind_check CHECK (kind IN ('FREE', 'TRIAL', 'PAID')),
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ALL and PAID are reserved words of the admin tenant list filter (« every plan », « every trial or paid plan »)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'plans_code_reserved_check' AND conrelid = 'public.plans'::regclass) THEN
        ALTER TABLE public.plans ADD CONSTRAINT plans_code_reserved_check CHECK (code NOT IN ('ALL', 'PAID'));
    END IF;
END $$;

INSERT INTO public.plans (code, label, sort_order, kind) VALUES
    ('FREE', 'Gratuit', 1, 'FREE'),
    ('PREMIUM_TRIAL', 'Essai Premium', 2, 'TRIAL'),
    ('PREMIUM', 'Premium', 3, 'PAID')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.plan_settings (
    id              SMALLINT     PRIMARY KEY CONSTRAINT plan_settings_single_row CHECK (id = 1),
    free_plan_code  VARCHAR(30)  NOT NULL
                    CONSTRAINT fk_plan_settings_free_plan REFERENCES public.plans (code),
    trial_plan_code VARCHAR(30)  NOT NULL
                    CONSTRAINT fk_plan_settings_trial_plan REFERENCES public.plans (code),
    trial_months    INTEGER      NOT NULL CONSTRAINT plan_settings_trial_months_check CHECK (trial_months > 0),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO public.plan_settings (id, free_plan_code, trial_plan_code, trial_months)
VALUES (1, 'FREE', 'PREMIUM_TRIAL', 6)
ON CONFLICT (id) DO NOTHING;

-- ── closed lists of codes → foreign keys to the catalogue ─────────────────────────────────────────────────────
ALTER TABLE public.plan_profiles DROP CONSTRAINT IF EXISTS plan_profiles_plan_type_check;
ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_from_plan_check;
ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_to_plan_check;

ALTER TABLE public.tenants ALTER COLUMN plan_type TYPE VARCHAR(30);
ALTER TABLE public.plan_profiles ALTER COLUMN plan_type TYPE VARCHAR(30);
ALTER TABLE public.profile_modules ALTER COLUMN plan_type TYPE VARCHAR(30);
ALTER TABLE public.tenant_plan_events ALTER COLUMN from_plan TYPE VARCHAR(30);
ALTER TABLE public.tenant_plan_events ALTER COLUMN to_plan TYPE VARCHAR(30);

DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT * FROM (VALUES
            ('tenants', 'fk_tenants_plan', 'plan_type'),
            ('plan_profiles', 'fk_plan_profiles_plan', 'plan_type'),
            ('tenant_plan_events', 'fk_tenant_plan_events_from_plan', 'from_plan'),
            ('tenant_plan_events', 'fk_tenant_plan_events_to_plan', 'to_plan')) AS v (tbl, name, col)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = fk.name AND conrelid = format('public.%I', fk.tbl)::regclass) THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES public.plans (code) '
                           'NOT VALID', fk.tbl, fk.name, fk.col);
        END IF;
        BEGIN
            EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I', fk.tbl, fk.name);
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE WARNING 'V42: % keeps rows with a code outside public.plans — % left NOT VALID (new rows checked)',
                fk.tbl, fk.name;
        END;
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS ix_tenants_plan_type ON public.tenants (plan_type);
