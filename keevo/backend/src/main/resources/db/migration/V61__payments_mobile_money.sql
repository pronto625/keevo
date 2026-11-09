-- V61 — Story 21.1: self-service plan payment by Mobile Money (MyCoolPay), module `payments`.
--
--   payment_requests            one checkout of an owner: the quote frozen at initiation (21.10 OfferPricing.quote),
--                               the provider references and the state machine
--                               INITIATING → PENDING → CONFIRMED | FAILED | CANCELLED | EXPIRED. At most one
--                               INITIATING/PENDING request per tenant (partial unique index = the « INITIATING lock »).
--                               A webhook finds its request by (provider, provider_transaction_ref) without knowing the
--                               tenant: platform data, public schema only.
--   discounts                   + redemption_count (confirmed uses, any scope) and max_redemptions (cap, promo codes)
--   tenant_payments             + payment_request_id: the ledger row written by a confirmed online payment (unique: a
--                               replayed webhook can never record the same payment twice)
--   tenant_plan_events.cause    + SELF_SERVICE_PAYMENT
--
-- Idempotent (IF NOT EXISTS / guarded blocks).
SET LOCAL lock_timeout = '10s';

-- ── payment requests ──────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.payment_requests (
    id                       UUID          PRIMARY KEY,
    tenant_id                UUID          NOT NULL
                             CONSTRAINT fk_payment_requests_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    tenant_schema            VARCHAR(63)   NOT NULL,
    requested_by             UUID          NOT NULL,
    provider                 VARCHAR(20)   NOT NULL
                             CONSTRAINT payment_requests_provider_check
                             CHECK (provider IN ('MYCOOLPAY', 'SANDBOX', 'NONE')),
    method                   VARCHAR(20)
                             CONSTRAINT payment_requests_method_check CHECK (method IN ('ORANGE_MONEY', 'MTN_MOMO')),
    phone                    VARCHAR(20),
    plan_code                VARCHAR(30)   NOT NULL
                             CONSTRAINT fk_payment_requests_plan REFERENCES public.plans (code),
    cycle_code               VARCHAR(30)   NOT NULL
                             CONSTRAINT fk_payment_requests_cycle REFERENCES public.billing_cycles (code),
    plan_label               VARCHAR(80)   NOT NULL,
    cycle_label              VARCHAR(80)   NOT NULL,
    months                   INTEGER       NOT NULL CONSTRAINT payment_requests_months_check CHECK (months > 0),
    base_xaf                 BIGINT        NOT NULL CONSTRAINT payment_requests_base_check CHECK (base_xaf > 0),
    reduction_xaf            BIGINT        NOT NULL DEFAULT 0
                             CONSTRAINT payment_requests_reduction_check CHECK (reduction_xaf >= 0),
    amount_due_xaf           BIGINT        NOT NULL
                             CONSTRAINT payment_requests_amount_check CHECK (amount_due_xaf >= 0),
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'XAF',
    discount_id              UUID
                             CONSTRAINT fk_payment_requests_discount REFERENCES public.discounts (id) ON DELETE SET NULL,
    discount_label           VARCHAR(120),
    promo_code               VARCHAR(40),
    priced_at                TIMESTAMPTZ   NOT NULL,
    status                   VARCHAR(20)   NOT NULL
                             CONSTRAINT payment_requests_status_check
                             CHECK (status IN ('INITIATING', 'PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    next_step                VARCHAR(10)
                             CONSTRAINT payment_requests_next_step_check CHECK (next_step IN ('OTP', 'USSD', 'NONE')),
    ussd_code                VARCHAR(80),
    app_transaction_ref      VARCHAR(80)   NOT NULL,
    provider_transaction_ref VARCHAR(100),
    provider_status          VARCHAR(20),
    failure_reason           VARCHAR(300),
    tenant_payment_id        UUID,
    period_start             DATE,
    period_end               DATE,
    expires_at               TIMESTAMPTZ,
    confirmed_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT payment_requests_due_check CHECK (amount_due_xaf = base_xaf - reduction_xaf),
    CONSTRAINT payment_requests_confirmed_check CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);

-- the « INITIATING lock »: one request in flight per tenant (a concurrent checkout hits this index)
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_requests_in_flight
    ON public.payment_requests (tenant_id) WHERE status IN ('INITIATING', 'PENDING');
-- webhook routing (provider reference → request)
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_requests_provider_ref
    ON public.payment_requests (provider, provider_transaction_ref) WHERE provider_transaction_ref IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_requests_app_ref ON public.payment_requests (app_transaction_ref);
-- the requests of a tenant, newest first (owner history, tenant purge cascade)
CREATE INDEX IF NOT EXISTS ix_payment_requests_tenant_created
    ON public.payment_requests (tenant_id, created_at DESC);
-- ON DELETE SET NULL of a purged discount
CREATE INDEX IF NOT EXISTS ix_payment_requests_discount
    ON public.payment_requests (discount_id) WHERE discount_id IS NOT NULL;

-- ── promo-code counters ───────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.discounts ADD COLUMN IF NOT EXISTS redemption_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.discounts ADD COLUMN IF NOT EXISTS max_redemptions INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'discounts_redemption_count_check' AND conrelid = 'public.discounts'::regclass) THEN
        ALTER TABLE public.discounts ADD CONSTRAINT discounts_redemption_count_check CHECK (redemption_count >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'discounts_max_redemptions_check' AND conrelid = 'public.discounts'::regclass) THEN
        ALTER TABLE public.discounts ADD CONSTRAINT discounts_max_redemptions_check
            CHECK (max_redemptions IS NULL OR (scope = 'PROMO_CODE' AND max_redemptions > 0));
    END IF;
END $$;

-- ── ledger row of an online payment ───────────────────────────────────────────────────────────────────────────
ALTER TABLE public.tenant_payments ADD COLUMN IF NOT EXISTS payment_request_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'fk_tenant_payments_request' AND conrelid = 'public.tenant_payments'::regclass) THEN
        ALTER TABLE public.tenant_payments ADD CONSTRAINT fk_tenant_payments_request
            FOREIGN KEY (payment_request_id) REFERENCES public.payment_requests (id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_payments_request
    ON public.tenant_payments (payment_request_id) WHERE payment_request_id IS NOT NULL;

-- ── plan event cause: + SELF_SERVICE_PAYMENT ──────────────────────────────────────────────────────────────────
-- same shape as V43 (NOT VALID then VALIDATE); inside the one Flyway transaction the exclusive lock lasts to the
-- commit anyway — a small platform table
ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_cause_check;
ALTER TABLE public.tenant_plan_events ADD CONSTRAINT tenant_plan_events_cause_check
    CHECK (cause IN ('ADMIN_ACTIVATION', 'ADMIN_DOWNGRADE', 'EXPIRY', 'REGISTRATION', 'REALIGNMENT',
                     'PLAN_MIGRATION', 'SELF_SERVICE_PAYMENT')) NOT VALID;
ALTER TABLE public.tenant_plan_events VALIDATE CONSTRAINT tenant_plan_events_cause_check;
