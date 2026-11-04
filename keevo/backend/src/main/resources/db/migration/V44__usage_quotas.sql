-- V44 — Story 19.8 (fusion Keevo × Bref, epic 19, ADR-05, FUS-FR8): usage counters and plan quotas as data.
--
--   plan_quotas     the quota of a plan per metric (DOCUMENTS | SMS | EMAILS | AI_CREDITS) and period kind
--                   (MONTH | DAY | LIFETIME); no row = unlimited. Starting values from Bref's plan policy.
--   usage_counters  what a tenant consumed per metric, period kind and period key ('2026-11' month, '2026-11-04' day,
--                   'LIFETIME'), incremented by one guarded upsert (UsageQuotaService): a race can never overshoot a
--                   quota. MONTH and DAY are always counted (with or without a limit); LIFETIME only under a quota.
--   usage_credits   purchased packs per tenant and metric, consumed once a MONTH or LIFETIME quota is used up
--                   (never for the DAY anti-abuse cap). Filled by the payments of 21.x.
--
-- Idempotent; public schema only; the seed never overwrites an edited quota (ON CONFLICT DO NOTHING).

CREATE TABLE IF NOT EXISTS public.plan_quotas (
    plan_type   VARCHAR(30) NOT NULL REFERENCES public.plans (code),
    metric      VARCHAR(20) NOT NULL CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')),
    period      VARCHAR(10) NOT NULL CHECK (period IN ('MONTH', 'DAY', 'LIFETIME')),
    quota_limit INTEGER     NOT NULL CHECK (quota_limit >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_type, metric, period)
);

CREATE TABLE IF NOT EXISTS public.usage_counters (
    tenant_id  UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    metric     VARCHAR(20) NOT NULL CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')),
    period     VARCHAR(10) NOT NULL CHECK (period IN ('MONTH', 'DAY', 'LIFETIME')),
    period_key VARCHAR(16) NOT NULL,
    used       BIGINT      NOT NULL DEFAULT 0 CHECK (used >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, metric, period_key)
);

-- the nightly purge deletes old DAY / MONTH counters by period kind and key (LIFETIME counters are never purged)
CREATE INDEX IF NOT EXISTS ix_usage_counters_period_key ON public.usage_counters (period, period_key)
    WHERE period <> 'LIFETIME';

CREATE TABLE IF NOT EXISTS public.usage_credits (
    tenant_id  UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    metric     VARCHAR(20) NOT NULL CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')),
    remaining  BIGINT      NOT NULL DEFAULT 0 CHECK (remaining >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, metric)
);

-- ── seed: Bref's plan policy (FREE 300 documents / 5 SMS for life / 100 emails / no AI; paid SMS and AI grids) ──
-- BUSINESS_TRIAL and the running PREMIUM_TRIAL trials mirror BUSINESS; the archived PREMIUM (moved to TEAM by 19.7b)
-- mirrors TEAM. Only plans present in the catalogue are seeded (foreign key).
INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit)
SELECT q.plan_type, q.metric, q.period, q.quota_limit
FROM (VALUES
        ('FREE', 'DOCUMENTS', 'MONTH', 300),
        ('FREE', 'SMS', 'LIFETIME', 5),
        ('FREE', 'EMAILS', 'MONTH', 100),
        ('FREE', 'AI_CREDITS', 'MONTH', 0),
        ('FREE', 'AI_CREDITS', 'DAY', 0),
        ('PRO', 'SMS', 'MONTH', 20),
        ('PRO', 'AI_CREDITS', 'MONTH', 500),
        ('PRO', 'AI_CREDITS', 'DAY', 20),
        ('TEAM', 'SMS', 'MONTH', 50),
        ('TEAM', 'AI_CREDITS', 'MONTH', 750),
        ('TEAM', 'AI_CREDITS', 'DAY', 30),
        ('BUSINESS', 'SMS', 'MONTH', 80),
        ('BUSINESS', 'AI_CREDITS', 'DAY', 50),
        ('BUSINESS_TRIAL', 'SMS', 'MONTH', 80),
        ('BUSINESS_TRIAL', 'AI_CREDITS', 'DAY', 50),
        ('PREMIUM_TRIAL', 'SMS', 'MONTH', 80),
        ('PREMIUM_TRIAL', 'AI_CREDITS', 'DAY', 50),
        ('PREMIUM', 'SMS', 'MONTH', 50),
        ('PREMIUM', 'AI_CREDITS', 'MONTH', 750),
        ('PREMIUM', 'AI_CREDITS', 'DAY', 30)
     ) AS q (plan_type, metric, period, quota_limit)
WHERE EXISTS (SELECT 1 FROM public.plans p WHERE p.code = q.plan_type)
ON CONFLICT (plan_type, metric, period) DO NOTHING;
