-- V65 — Story 21.4 (fusion Keevo × Bref, epic 21, FUS-FR27 / FR39): the new module « Assistant IA » (ai-assistant).
--
--   profile_modules           ai-assistant granted to EVERY existing plan profile, FREE included: Bref Point (guided
--                             topics, saved conversations) is a tab of every Bref account; free-text questions stay
--                             bounded by the AI_CREDITS quotas of the plan (V44: FREE 0 → refused with QUOTA_EXCEEDED,
--                             PRO 500 / month and 20 / day, TEAM 750 / 30, BUSINESS unlimited / 50…). The offer is data
--                             (ADR-05): a super-admin may withdraw it from a plan. Existing profiles only.
--   tenant_module_activation  ai-assistant owner-disabled for every existing tenant (db/modules/README.md « Modules
--                             introduced after the go-live »): it stays out of their menus until their owner enables it
--                             in « Mes modules ». Tenants created afterwards follow the initial activation rules.
--
-- Idempotent (ON CONFLICT DO NOTHING: an edited grant or an owner choice is never overwritten); public schema only;
-- set-based.
SET LOCAL lock_timeout = '10s';

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, 'ai-assistant', true
FROM public.plan_profiles p
ON CONFLICT (plan_type, module_id) DO NOTHING;

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'ai-assistant', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
