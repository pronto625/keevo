-- V33 — Story 16.8: plan profiles and configurable limits (MOD-FR13b).
--
--   plan_profiles    one row per plan type: store / product / employee limits (NULL = unlimited). Read live by the
--                    plan-limit guard, GET /subscription/me and the subscription writers (legacy max_* columns).
--   profile_modules  explicit decisions on the PAID modules (manifests with free = false): granted true / false per
--                    plan type. A paid module without a row follows the plan type's default — PREMIUM and
--                    PREMIUM_TRIAL grant it, FREE does not — so a paid module shipped later stays available to
--                    Premium until a super-admin decides otherwise. Free and core modules are available to every
--                    plan and are never listed here. Read live by the plan-derived tenant_modules rows (16.5
--                    PlanModuleRows) and the no-row transition rule.
--
-- Seed = the validated defaults: FREE 1 store / 500 products / 2 employees (journal I14), no paid module (no rows:
-- the FREE default); PREMIUM_TRIAL and PREMIUM unlimited with every paid module installed today (granted rows). No foreign key to
-- module_definitions: that catalogue is written at boot, after Flyway; the super-admin API validates module ids
-- against the installed manifests. Idempotent (ON CONFLICT DO NOTHING keeps an edited profile). Public schema only.
CREATE TABLE IF NOT EXISTS public.plan_profiles (
    plan_type     VARCHAR(20) PRIMARY KEY
                  CONSTRAINT plan_profiles_plan_type_check CHECK (plan_type IN ('FREE', 'PREMIUM_TRIAL', 'PREMIUM')),
    max_stores    INTEGER CONSTRAINT plan_profiles_max_stores_check CHECK (max_stores IS NULL OR max_stores > 0),
    max_products  INTEGER CONSTRAINT plan_profiles_max_products_check CHECK (max_products IS NULL OR max_products > 0),
    max_employees INTEGER CONSTRAINT plan_profiles_max_employees_check CHECK (max_employees IS NULL OR max_employees > 0),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    UUID
);

CREATE TABLE IF NOT EXISTS public.profile_modules (
    plan_type VARCHAR(20) NOT NULL REFERENCES public.plan_profiles (plan_type) ON DELETE CASCADE,
    module_id VARCHAR(50) NOT NULL
              CONSTRAINT profile_modules_module_id_check CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    granted   BOOLEAN     NOT NULL,
    PRIMARY KEY (plan_type, module_id)
);

INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, max_employees) VALUES
    ('FREE', 1, 500, 2),
    ('PREMIUM_TRIAL', NULL, NULL, NULL),
    ('PREMIUM', NULL, NULL, NULL)
ON CONFLICT (plan_type) DO NOTHING;

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, m.module_id, true
FROM (VALUES ('PREMIUM_TRIAL'), ('PREMIUM')) AS p (plan_type)
CROSS JOIN (VALUES ('counting'), ('exports'), ('profitability'), ('alerts'), ('report-config'), ('invoicing'),
                   ('multi-store'), ('advanced-reports')) AS m (module_id)
ON CONFLICT (plan_type, module_id) DO NOTHING;
