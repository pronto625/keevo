-- V63 — Story 21.3: account credit and usage packs (SMS first).
--
--   usage_packs               packs of a usage metric sold to owners (commercial offer data, module subscription.offer,
--                             edited by the super-admin, ADR-05). Seeded from Bref's price of 20 FCFA per SMS.
--   offer_change_log.entity   + USAGE_PACK.
--   tenant_credits            the account credit of a company (FCFA, never negative).
--   tenant_credit_movements   append-only journal of every credit change (signed amount, balance after, kind, the
--                             payment request concerned, actor, support reason).
--   usage_credit_movements    append-only journal of the pack balances (public.usage_credits, 19.8): each purchase and
--                             each unit consumed from a pack (actor, metric, quota period that was exhausted).
--   payment_requests          + kind (PLAN | USAGE_PACK), + credit_applied_xaf (credit reserved for the request), + the
--                             pack bought (frozen code, label, metric, quantity); the plan columns become optional (a
--                             pack request has none), a shape check keeps each kind complete.
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS / guarded blocks / ON CONFLICT DO NOTHING).
SET LOCAL lock_timeout = '10s';

-- ── usage packs (offer data) ──────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.usage_packs (
    code        VARCHAR(30)   PRIMARY KEY
                CONSTRAINT usage_packs_code_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    label       VARCHAR(80)   NOT NULL,
    metric      VARCHAR(20)   NOT NULL
                CONSTRAINT usage_packs_metric_check CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')),
    quantity    INTEGER       NOT NULL CONSTRAINT usage_packs_quantity_check CHECK (quantity BETWEEN 1 AND 1000000),
    price_xaf   BIGINT        NOT NULL CONSTRAINT usage_packs_price_check CHECK (price_xaf BETWEEN 1 AND 100000000),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO public.usage_packs (code, label, metric, quantity, price_xaf, sort_order) VALUES
    ('SMS_100', 'Pack 100 SMS', 'SMS', 100, 2000, 10),
    ('SMS_250', 'Pack 250 SMS', 'SMS', 250, 5000, 20),
    ('SMS_500', 'Pack 500 SMS', 'SMS', 500, 10000, 30),
    ('SMS_1000', 'Pack 1 000 SMS', 'SMS', 1000, 20000, 40)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE public.offer_change_log DROP CONSTRAINT IF EXISTS offer_change_log_entity_check;
ALTER TABLE public.offer_change_log ADD CONSTRAINT offer_change_log_entity_check
    CHECK (entity IN ('PLAN', 'CYCLE', 'PRICE', 'SETTINGS', 'DISCOUNT', 'STORE_PRODUCT', 'USAGE_PACK')) NOT VALID;
ALTER TABLE public.offer_change_log VALIDATE CONSTRAINT offer_change_log_entity_check;

-- ── payment requests: plan or pack, credit applied ────────────────────────────────────────────────────────────
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'PLAN';
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS credit_applied_xaf BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS pack_code VARCHAR(30);
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS pack_label VARCHAR(80);
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS pack_metric VARCHAR(20);
ALTER TABLE public.payment_requests ADD COLUMN IF NOT EXISTS pack_quantity INTEGER;
ALTER TABLE public.payment_requests ALTER COLUMN plan_code DROP NOT NULL;
ALTER TABLE public.payment_requests ALTER COLUMN cycle_code DROP NOT NULL;
ALTER TABLE public.payment_requests ALTER COLUMN plan_label DROP NOT NULL;
ALTER TABLE public.payment_requests ALTER COLUMN cycle_label DROP NOT NULL;
ALTER TABLE public.payment_requests ALTER COLUMN months DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'payment_requests_kind_check' AND conrelid = 'public.payment_requests'::regclass) THEN
        ALTER TABLE public.payment_requests ADD CONSTRAINT payment_requests_kind_check
            CHECK (kind IN ('PLAN', 'USAGE_PACK'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'payment_requests_shape_check' AND conrelid = 'public.payment_requests'::regclass) THEN
        ALTER TABLE public.payment_requests ADD CONSTRAINT payment_requests_shape_check
            CHECK ((kind = 'PLAN' AND plan_code IS NOT NULL AND cycle_code IS NOT NULL AND plan_label IS NOT NULL
                    AND cycle_label IS NOT NULL AND months IS NOT NULL AND pack_code IS NULL)
                OR (kind = 'USAGE_PACK' AND pack_code IS NOT NULL AND pack_label IS NOT NULL
                    AND pack_metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')
                    AND pack_quantity > 0 AND plan_code IS NULL AND cycle_code IS NULL AND months IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_payment_requests_pack' AND conrelid = 'public.payment_requests'::regclass) THEN
        ALTER TABLE public.payment_requests ADD CONSTRAINT fk_payment_requests_pack
            FOREIGN KEY (pack_code) REFERENCES public.usage_packs (code);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'payment_requests_credit_check' AND conrelid = 'public.payment_requests'::regclass) THEN
        ALTER TABLE public.payment_requests ADD CONSTRAINT payment_requests_credit_check
            CHECK (credit_applied_xaf >= 0 AND credit_applied_xaf <= base_xaf - reduction_xaf);
    END IF;
END $$;

-- the amount charged to the provider is what the credit does not cover
ALTER TABLE public.payment_requests DROP CONSTRAINT IF EXISTS payment_requests_due_check;
ALTER TABLE public.payment_requests ADD CONSTRAINT payment_requests_due_check
    CHECK (amount_due_xaf = base_xaf - reduction_xaf - credit_applied_xaf);

-- FK of a pack (catalogue edits), pack sales of the admin revenue summary
CREATE INDEX IF NOT EXISTS ix_payment_requests_pack ON public.payment_requests (pack_code) WHERE pack_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_payment_requests_pack_sales
    ON public.payment_requests (confirmed_at) WHERE kind = 'USAGE_PACK' AND status = 'CONFIRMED';

-- ── account credit ────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.tenant_credits (
    tenant_id    UUID         PRIMARY KEY
                 CONSTRAINT fk_tenant_credits_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    balance_xaf  BIGINT       NOT NULL DEFAULT 0 CONSTRAINT tenant_credits_balance_check CHECK (balance_xaf >= 0),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.tenant_credit_movements (
    id                  UUID          PRIMARY KEY,
    tenant_id           UUID          NOT NULL
                        CONSTRAINT fk_tenant_credit_movements_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    amount_xaf          BIGINT        NOT NULL CONSTRAINT tenant_credit_movements_amount_check CHECK (amount_xaf <> 0),
    balance_after_xaf   BIGINT        NOT NULL
                        CONSTRAINT tenant_credit_movements_balance_check CHECK (balance_after_xaf >= 0),
    kind                VARCHAR(30)   NOT NULL
                        CONSTRAINT tenant_credit_movements_kind_check
                        CHECK (kind IN ('ADMIN_ADJUSTMENT', 'PAYMENT_USE', 'PAYMENT_RELEASE', 'PAYMENT_TO_CREDIT',
                                        'MIGRATION')),
    payment_request_id  UUID
                        CONSTRAINT fk_tenant_credit_movements_request REFERENCES public.payment_requests (id),
    actor_id            UUID,
    reason              VARCHAR(300),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp()
);

-- the history of a company, newest first (keyset on created_at, id)
CREATE INDEX IF NOT EXISTS ix_tenant_credit_movements_tenant
    ON public.tenant_credit_movements (tenant_id, created_at DESC, id DESC);
-- FK of a request; a late payment is converted to credit at most once
CREATE INDEX IF NOT EXISTS ix_tenant_credit_movements_request
    ON public.tenant_credit_movements (payment_request_id) WHERE payment_request_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_credit_movements_converted
    ON public.tenant_credit_movements (payment_request_id) WHERE kind = 'PAYMENT_TO_CREDIT';

-- ── pack ledger ───────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.usage_credit_movements (
    id                  UUID          PRIMARY KEY,
    tenant_id           UUID          NOT NULL
                        CONSTRAINT fk_usage_credit_movements_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    metric              VARCHAR(20)   NOT NULL
                        CONSTRAINT usage_credit_movements_metric_check
                        CHECK (metric IN ('DOCUMENTS', 'SMS', 'EMAILS', 'AI_CREDITS')),
    period              VARCHAR(10)
                        CONSTRAINT usage_credit_movements_period_check CHECK (period IN ('MONTH', 'LIFETIME')),
    delta               INTEGER       NOT NULL CONSTRAINT usage_credit_movements_delta_check CHECK (delta <> 0),
    remaining_after     BIGINT        NOT NULL
                        CONSTRAINT usage_credit_movements_remaining_check CHECK (remaining_after >= 0),
    source              VARCHAR(20)   NOT NULL
                        CONSTRAINT usage_credit_movements_source_check
                        CHECK (source IN ('PURCHASE', 'CONSUMPTION', 'MIGRATION')),
    payment_request_id  UUID
                        CONSTRAINT fk_usage_credit_movements_request REFERENCES public.payment_requests (id),
    actor_id            UUID,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp()
);

-- the history of a company (all metrics or one), newest first; the month's consumption of « Mon plan »
CREATE INDEX IF NOT EXISTS ix_usage_credit_movements_tenant
    ON public.usage_credit_movements (tenant_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_usage_credit_movements_tenant_metric
    ON public.usage_credit_movements (tenant_id, metric, created_at DESC, id DESC);
-- the pack units consumed this month (« Mon plan », every GET /subscription/me): index-only range scan
CREATE INDEX IF NOT EXISTS ix_usage_credit_movements_consumed
    ON public.usage_credit_movements (tenant_id, created_at) INCLUDE (metric, delta) WHERE source = 'CONSUMPTION';
-- a paid pack is credited at most once
CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_credit_movements_purchase
    ON public.usage_credit_movements (payment_request_id) WHERE source = 'PURCHASE';
CREATE INDEX IF NOT EXISTS ix_usage_credit_movements_request
    ON public.usage_credit_movements (payment_request_id) WHERE payment_request_id IS NOT NULL;

-- ── append-only journals (deletes stay possible: tenant purge, ON DELETE CASCADE) ──────────────────────────────
CREATE OR REPLACE FUNCTION public.keevo_ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE OR REPLACE TRIGGER trg_tenant_credit_movements_no_update
    BEFORE UPDATE ON public.tenant_credit_movements
    FOR EACH ROW EXECUTE FUNCTION public.keevo_ledger_append_only();
CREATE OR REPLACE TRIGGER trg_tenant_credit_movements_no_truncate
    BEFORE TRUNCATE ON public.tenant_credit_movements
    FOR EACH STATEMENT EXECUTE FUNCTION public.keevo_ledger_append_only();
CREATE OR REPLACE TRIGGER trg_usage_credit_movements_no_update
    BEFORE UPDATE ON public.usage_credit_movements
    FOR EACH ROW EXECUTE FUNCTION public.keevo_ledger_append_only();
CREATE OR REPLACE TRIGGER trg_usage_credit_movements_no_truncate
    BEFORE TRUNCATE ON public.usage_credit_movements
    FOR EACH STATEMENT EXECUTE FUNCTION public.keevo_ledger_append_only();
