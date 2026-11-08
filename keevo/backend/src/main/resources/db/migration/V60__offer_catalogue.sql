-- V60 — Story 21.10 (epic 21, ADR-05, FUS-FR40): the whole commercial offer is data, edited by the super-admin.
--
--   billing_cycles      the billing durations offered (MONTHLY « Mensuel » 1 month, QUARTERLY « Trimestriel » 3,
--                       ANNUAL « Annuel » 12); archived, never deleted.
--   plan_prices         the price of a paid plan for a cycle, in XAF (Mobile Money and granted plans; Apple prices
--                       live in App Store Connect). Seeded with Bref's grid.
--   plan_price_history  every price change (old → new, author, instant); the seed rows have no author
--                       (« Données de départ »). A deleted price is a row whose new amount is null.
--   discounts           PROMOTION (dated, every tenant), TENANT (one company), PROMO_CODE (typed by the owner):
--                       exactly one of percent_off / amount_off_xaf; plan_codes / cycle_codes empty = all. The best
--                       single discount applies (no stacking, OfferPricer).
--   offer_change_log    append-only journal of every catalogue change (French summary, actor, instant).
--   plan_settings       + trial_enabled: a new registration starts on the trial plan, or on the free plan when off.
--   tenant_payments     + plan_code / cycle_code: the plan and cycle a recorded payment paid for (revenue per plan);
--                       legacy rows get their plan from the activation event recorded with them (set-based backfill).
--
-- Platform catalogue, public schema only. Idempotent; the seeds never overwrite an edited row (ON CONFLICT DO NOTHING).

-- ── billing cycles ────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.billing_cycles (
    code        VARCHAR(30)  PRIMARY KEY
                CONSTRAINT billing_cycles_code_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    label       VARCHAR(80)  NOT NULL,
    months      INTEGER      NOT NULL CONSTRAINT billing_cycles_months_check CHECK (months BETWEEN 1 AND 36),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO public.billing_cycles (code, label, months, sort_order) VALUES
    ('MONTHLY', 'Mensuel', 1, 10),
    ('QUARTERLY', 'Trimestriel', 3, 20),
    ('ANNUAL', 'Annuel', 12, 30)
ON CONFLICT (code) DO NOTHING;

-- ── prices and their history ──────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.plan_prices (
    plan_code   VARCHAR(30)  NOT NULL CONSTRAINT fk_plan_prices_plan REFERENCES public.plans (code),
    cycle_code  VARCHAR(30)  NOT NULL CONSTRAINT fk_plan_prices_cycle REFERENCES public.billing_cycles (code),
    amount_xaf  INTEGER      NOT NULL
                CONSTRAINT plan_prices_amount_check CHECK (amount_xaf > 0 AND amount_xaf <= 100000000),
    updated_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_code, cycle_code)
);

CREATE TABLE IF NOT EXISTS public.plan_price_history (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code       VARCHAR(30)  NOT NULL CONSTRAINT fk_plan_price_history_plan REFERENCES public.plans (code),
    cycle_code      VARCHAR(30)  NOT NULL
                    CONSTRAINT fk_plan_price_history_cycle REFERENCES public.billing_cycles (code),
    old_amount_xaf  INTEGER,
    new_amount_xaf  INTEGER,
    changed_by      UUID,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_plan_price_history_price
    ON public.plan_price_history (plan_code, cycle_code, changed_at DESC);
CREATE INDEX IF NOT EXISTS ix_plan_price_history_cycle
    ON public.plan_price_history (cycle_code, changed_at DESC);
CREATE INDEX IF NOT EXISTS ix_plan_price_history_changed ON public.plan_price_history (changed_at DESC, id DESC);

-- Bref's XAF grid (seed data only); a history row per price actually inserted (none on a rerun)
WITH grid (plan_code, cycle_code, amount_xaf) AS (VALUES
        ('PRO', 'MONTHLY', 3500), ('PRO', 'QUARTERLY', 9500), ('PRO', 'ANNUAL', 32760),
        ('TEAM', 'MONTHLY', 5500), ('TEAM', 'QUARTERLY', 15000), ('TEAM', 'ANNUAL', 51480),
        ('BUSINESS', 'MONTHLY', 8500), ('BUSINESS', 'QUARTERLY', 23000), ('BUSINESS', 'ANNUAL', 79560)),
inserted AS (
    INSERT INTO public.plan_prices (plan_code, cycle_code, amount_xaf)
    SELECT g.plan_code, g.cycle_code, g.amount_xaf
    FROM grid g
    JOIN public.plans p ON p.code = g.plan_code
    JOIN public.billing_cycles c ON c.code = g.cycle_code
    ON CONFLICT (plan_code, cycle_code) DO NOTHING
    RETURNING plan_code, cycle_code, amount_xaf)
INSERT INTO public.plan_price_history (plan_code, cycle_code, old_amount_xaf, new_amount_xaf)
SELECT plan_code, cycle_code, NULL, amount_xaf FROM inserted;

-- ── discounts ─────────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.discounts (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    scope           VARCHAR(20)   NOT NULL
                    CONSTRAINT discounts_scope_check CHECK (scope IN ('PROMOTION', 'TENANT', 'PROMO_CODE')),
    label           VARCHAR(120)  NOT NULL,
    percent_off     NUMERIC(5, 2)
                    CONSTRAINT discounts_percent_check CHECK (percent_off > 0 AND percent_off <= 100),
    amount_off_xaf  INTEGER
                    CONSTRAINT discounts_amount_check CHECK (amount_off_xaf > 0 AND amount_off_xaf <= 100000000),
    plan_codes      VARCHAR(30)[] NOT NULL DEFAULT '{}',
    cycle_codes     VARCHAR(30)[] NOT NULL DEFAULT '{}',
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    tenant_id       UUID          CONSTRAINT fk_discounts_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    promo_code      VARCHAR(40)
                    CONSTRAINT discounts_promo_code_check CHECK (promo_code ~ '^[A-Z0-9_-]{3,40}$'),
    archived_at     TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by      UUID,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT discounts_one_value_check CHECK ((percent_off IS NULL) <> (amount_off_xaf IS NULL)),
    CONSTRAINT discounts_tenant_scope_check CHECK ((scope = 'TENANT') = (tenant_id IS NOT NULL)),
    CONSTRAINT discounts_promo_scope_check CHECK ((scope = 'PROMO_CODE') = (promo_code IS NOT NULL)),
    CONSTRAINT discounts_promotion_end_check CHECK (scope <> 'PROMOTION' OR ends_at IS NOT NULL),
    CONSTRAINT discounts_window_check CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);

-- a live promo code is unique whatever its case; the discounts of a company are indexed (live lookups and the
-- ON DELETE CASCADE of the company); live promotions are indexed
CREATE UNIQUE INDEX IF NOT EXISTS ux_discounts_live_promo_code
    ON public.discounts (upper(promo_code)) WHERE promo_code IS NOT NULL AND archived_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_discounts_tenant ON public.discounts (tenant_id);
CREATE INDEX IF NOT EXISTS ix_discounts_live_promotion
    ON public.discounts (ends_at) WHERE scope = 'PROMOTION' AND archived_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_discounts_created ON public.discounts (created_at DESC);

-- ── catalogue change journal ──────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.offer_change_log (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    entity      VARCHAR(20)   NOT NULL
                CONSTRAINT offer_change_log_entity_check
                CHECK (entity IN ('PLAN', 'CYCLE', 'PRICE', 'SETTINGS', 'DISCOUNT')),
    entity_key  VARCHAR(80)   NOT NULL,
    summary     VARCHAR(500)  NOT NULL,
    actor_id    UUID,
    occurred_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_offer_change_log_occurred ON public.offer_change_log (occurred_at DESC, id DESC);

-- ── trial switch ──────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.plan_settings ADD COLUMN IF NOT EXISTS trial_enabled BOOLEAN NOT NULL DEFAULT true;

-- ── plan and cycle of each recorded payment ───────────────────────────────────────────────────────────────────
ALTER TABLE public.tenant_payments ADD COLUMN IF NOT EXISTS plan_code VARCHAR(30);
ALTER TABLE public.tenant_payments ADD COLUMN IF NOT EXISTS cycle_code VARCHAR(30);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_tenant_payments_plan' AND conrelid = 'public.tenant_payments'::regclass) THEN
        ALTER TABLE public.tenant_payments ADD CONSTRAINT fk_tenant_payments_plan
            FOREIGN KEY (plan_code) REFERENCES public.plans (code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_tenant_payments_cycle' AND conrelid = 'public.tenant_payments'::regclass) THEN
        ALTER TABLE public.tenant_payments ADD CONSTRAINT fk_tenant_payments_cycle
            FOREIGN KEY (cycle_code) REFERENCES public.billing_cycles (code);
    END IF;
END $$;

-- legacy payments: the plan of the latest paid-kind plan event of the tenant recorded with the payment (the
-- activation writes both in one transaction; one minute of tolerance). One set-based statement over the
-- (tenant_id, occurred_at DESC) index; the cycle stays unknown (« Durée libre »).
UPDATE public.tenant_payments target
SET plan_code = ev.to_plan
FROM public.tenant_payments p
CROSS JOIN LATERAL (
    SELECT e.to_plan
    FROM public.tenant_plan_events e
    JOIN public.plans pl ON pl.code = e.to_plan
    WHERE e.tenant_id = p.tenant_id
      AND pl.kind = 'PAID'
      AND e.occurred_at <= p.created_at + INTERVAL '1 minute'
    ORDER BY e.occurred_at DESC
    LIMIT 1) ev
WHERE target.id = p.id
  AND target.plan_code IS NULL;
