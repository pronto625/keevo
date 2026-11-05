-- V50 — Story 19.13 (fusion Keevo × Bref, epic 19, ADR-05): interface profile — owner activation of plan modules.
--
--   tenant_module_activation       the owner's choice per tenant module, apart from tenant_modules (entitlement):
--                                  a module is usable only when the plan includes it AND the owner did not disable
--                                  it. No row = activated. Plan sync, the super-admin lifecycle and the nightly expiry
--                                  job never read nor write it: an owner choice survives plan changes, and a plan or
--                                  admin change never re-enables an owner-disabled module.
--   module_activation_rule_sets    initial activation rules of a NEW tenant (onboarding, Bref import 24.2), by sale
--   module_activation_rule_modules flow and optional sector: the modules disabled at start. Resolution: the
--                                  (flow, sector) set if it exists, else (flow, NULL): the most specific set wins, no
--                                  merge. Editable by the super-admin; never applied to existing tenants.
--
-- Go-live backfill (same migration, so no FREE tenant ever sees invoicing enabled):
--   * every existing tenant gets invoicing owner-disabled (new to every user: it did not exist in V1, and on a FREE
--     tenant it would turn every POS sale into an automatic invoice); every other current module existed in V1 and
--     stays activated (no row);
--   * FREE is granted invoicing (profile_modules) — its 300 documents / month quota is enforced since 19.8.
--
-- Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING: an edited rule set or an owner choice is never overwritten);
-- public schema only; set-based (one INSERT … SELECT over the tenants).
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.tenant_module_activation (
    tenant_id  UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    module_id  VARCHAR(50) NOT NULL
               CONSTRAINT tenant_module_activation_module_id_check CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    activated  BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID,
    PRIMARY KEY (tenant_id, module_id)
);

CREATE TABLE IF NOT EXISTS public.module_activation_rule_sets (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    sale_flow   VARCHAR(10) NOT NULL
                CONSTRAINT module_activation_rule_sets_sale_flow_check CHECK (sale_flow IN ('POS', 'DOCUMENT')),
    sector_type VARCHAR(30),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT module_activation_rule_sets_flow_sector_key UNIQUE NULLS NOT DISTINCT (sale_flow, sector_type)
);

CREATE TABLE IF NOT EXISTS public.module_activation_rule_modules (
    rule_set_id UUID        NOT NULL REFERENCES public.module_activation_rule_sets (id) ON DELETE CASCADE,
    module_id   VARCHAR(50) NOT NULL
                CONSTRAINT module_activation_rule_modules_module_id_check
                    CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    PRIMARY KEY (rule_set_id, module_id)
);

-- ── default rule sets: (POS, any sector) disables invoicing (on FREE it would turn every till sale into an automatic
--    invoice and burn the documents quota: the owner enables it in « Mes modules »); (DOCUMENT, any sector) disables
--    the till and the stock (dependency-closed: pos, counting and multi-store require inventory) ──────────────────
INSERT INTO public.module_activation_rule_sets (sale_flow, sector_type) VALUES ('POS', NULL), ('DOCUMENT', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO public.module_activation_rule_modules (rule_set_id, module_id)
SELECT s.id, 'invoicing'
FROM public.module_activation_rule_sets s
WHERE s.sale_flow = 'POS' AND s.sector_type IS NULL
  AND NOT EXISTS (SELECT 1 FROM public.module_activation_rule_modules r WHERE r.rule_set_id = s.id)
ON CONFLICT DO NOTHING;

INSERT INTO public.module_activation_rule_modules (rule_set_id, module_id)
SELECT s.id, m.module_id
FROM public.module_activation_rule_sets s
CROSS JOIN (VALUES ('pos'), ('inventory'), ('counting'), ('multi-store')) AS m (module_id)
WHERE s.sale_flow = 'DOCUMENT' AND s.sector_type IS NULL
  AND NOT EXISTS (SELECT 1 FROM public.module_activation_rule_modules r WHERE r.rule_set_id = s.id)
ON CONFLICT DO NOTHING;

-- ── go-live backfill: invoicing owner-disabled for every existing tenant ─────────────────────────────────────────
INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'invoicing', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;

-- ── FREE granted invoicing (paid module explicitly granted to the free plan) ──────────────────────────────────────
INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT 'FREE', 'invoicing', true
WHERE EXISTS (SELECT 1 FROM public.plan_profiles WHERE plan_type = 'FREE')
ON CONFLICT (plan_type, module_id) DO NOTHING;
