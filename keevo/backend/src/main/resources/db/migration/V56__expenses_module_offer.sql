-- V56 — Story 20.10 (fusion Keevo × Bref, epic 20, FUS-FR25): the new premium module « Dépenses » (expenses).
--
--   profile_modules           expenses granted to the paid Bref grid from PRO up (PRO, TEAM, BUSINESS and the
--                             BUSINESS_TRIAL registration trial) and to the archived complete plans (PREMIUM,
--                             PREMIUM_TRIAL, which hold every paid module) — the offer is data (ADR-05): a super-admin
--                             may change it; FREE keeps the paid-module default (not granted). Existing profiles only.
--   tenant_module_activation  expenses owner-disabled for every existing tenant (db/modules/README.md « Modules
--                             introduced after the go-live »): new to every user, it stays out of their menus until
--                             their owner enables it in « Mes modules ». Tenants created afterwards follow the initial
--                             activation rules.
--
-- Idempotent (ON CONFLICT DO NOTHING: an edited grant or an owner choice is never overwritten); public schema only;
-- set-based.
SET LOCAL lock_timeout = '10s';

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, 'expenses', true
FROM public.plan_profiles p
WHERE p.plan_type IN ('PRO', 'TEAM', 'BUSINESS', 'BUSINESS_TRIAL', 'PREMIUM', 'PREMIUM_TRIAL')
ON CONFLICT (plan_type, module_id) DO NOTHING;

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'expenses', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
