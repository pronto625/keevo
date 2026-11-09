-- V62 — Story 21.2: App Store in-app purchases (StoreKit through RevenueCat), module `payments`.
--
--   store_products            App Store product → plan × cycle mapping (commercial offer data, edited by the
--                             super-admin, module subscription.offer); the declared Apple price (read in App Store
--                             Connect) and the last price observed in a RevenueCat event feed the gap with the grid.
--                             Seeded with Bref Point's six products when their plan and cycle exist.
--   external_subscriptions    one Apple subscription (original transaction id) bound to one company: status, Apple
--                             expiry, grace end, the expiry it granted, the last event instant (stale events dropped).
--   revenuecat_webhook_event  every RevenueCat event processed (the id is the de-duplication key; name kept from Bref
--                             for the epic-24 import of the events already processed by api-bref).
--   tenant_payments           + method APP_STORE, + store_transaction_id (unique: a replayed event never records a
--                             purchase twice).
--   offer_change_log.entity   + STORE_PRODUCT.
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS / guarded blocks / ON CONFLICT DO NOTHING).
SET LOCAL lock_timeout = '10s';

-- ── App Store products ────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.store_products (
    store               VARCHAR(20)    NOT NULL
                        CONSTRAINT store_products_store_check CHECK (store IN ('APP_STORE')),
    product_id          VARCHAR(100)   NOT NULL
                        CONSTRAINT store_products_product_check CHECK (product_id ~ '^[A-Za-z0-9._-]{1,100}$'),
    plan_code           VARCHAR(30)    NOT NULL
                        CONSTRAINT fk_store_products_plan REFERENCES public.plans (code),
    cycle_code          VARCHAR(30)    NOT NULL
                        CONSTRAINT fk_store_products_cycle REFERENCES public.billing_cycles (code),
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    declared_price_xaf  BIGINT
                        CONSTRAINT store_products_declared_check
                        CHECK (declared_price_xaf IS NULL OR declared_price_xaf BETWEEN 1 AND 100000000),
    observed_price      NUMERIC(14, 2),
    observed_currency   VARCHAR(3),
    observed_price_xaf  BIGINT,
    observed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_store_products PRIMARY KEY (store, product_id)
);

-- the products of a plan × cycle (catalogue gap, FK of a plan / cycle)
CREATE INDEX IF NOT EXISTS ix_store_products_plan_cycle ON public.store_products (plan_code, cycle_code);
CREATE INDEX IF NOT EXISTS ix_store_products_cycle ON public.store_products (cycle_code);

INSERT INTO public.store_products (store, product_id, plan_code, cycle_code)
SELECT 'APP_STORE', s.product_id, s.plan_code, s.cycle_code
FROM (VALUES ('brefpoint.pro.month', 'PRO', 'MONTHLY'),
             ('brefpoint.pro.yearly', 'PRO', 'ANNUAL'),
             ('brefpoint.team.monthly', 'TEAM', 'MONTHLY'),
             ('brefpoint.team.yearly', 'TEAM', 'ANNUAL'),
             ('brefpoint.business.monthly', 'BUSINESS', 'MONTHLY'),
             ('brefpoint.business.yearly', 'BUSINESS', 'ANNUAL')) AS s (product_id, plan_code, cycle_code)
WHERE EXISTS (SELECT 1 FROM public.plans p WHERE p.code = s.plan_code)
  AND EXISTS (SELECT 1 FROM public.billing_cycles c WHERE c.code = s.cycle_code)
ON CONFLICT (store, product_id) DO NOTHING;

-- ── external subscriptions ────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.external_subscriptions (
    id                       UUID          PRIMARY KEY,
    tenant_id                UUID          NOT NULL
                             CONSTRAINT fk_external_subscriptions_tenant REFERENCES public.tenants (id) ON DELETE CASCADE,
    tenant_schema            VARCHAR(63)   NOT NULL,
    store                    VARCHAR(20)   NOT NULL
                             CONSTRAINT external_subscriptions_store_check CHECK (store IN ('APP_STORE')),
    app_user_id              UUID          NOT NULL,
    original_transaction_id  VARCHAR(100)  NOT NULL,
    product_id               VARCHAR(100)  NOT NULL,
    plan_code                VARCHAR(30)   NOT NULL
                             CONSTRAINT fk_external_subscriptions_plan REFERENCES public.plans (code),
    cycle_code               VARCHAR(30)   NOT NULL
                             CONSTRAINT fk_external_subscriptions_cycle REFERENCES public.billing_cycles (code),
    status                   VARCHAR(20)   NOT NULL
                             CONSTRAINT external_subscriptions_status_check
                             CHECK (status IN ('ACTIVE', 'CANCELLED', 'BILLING_ISSUE', 'EXPIRED', 'REVOKED',
                                               'TRANSFERRED')),
    auto_renew               BOOLEAN       NOT NULL DEFAULT TRUE,
    environment              VARCHAR(20)   NOT NULL
                             CONSTRAINT external_subscriptions_environment_check
                             CHECK (environment IN ('PRODUCTION', 'SANDBOX')),
    expires_at               TIMESTAMPTZ,
    grace_expires_at         TIMESTAMPTZ,
    granted_expires_at       TIMESTAMPTZ,
    last_event_ms            BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- webhook routing: one Apple subscription belongs to one company
CREATE UNIQUE INDEX IF NOT EXISTS uq_external_subscriptions_original
    ON public.external_subscriptions (store, original_transaction_id);
-- the company's subscription, latest first (owner view, tenant purge cascade)
CREATE INDEX IF NOT EXISTS ix_external_subscriptions_tenant
    ON public.external_subscriptions (tenant_id, updated_at DESC);
-- TRANSFER: the subscriptions of a RevenueCat customer
CREATE INDEX IF NOT EXISTS ix_external_subscriptions_app_user
    ON public.external_subscriptions (app_user_id);
CREATE INDEX IF NOT EXISTS ix_external_subscriptions_plan ON public.external_subscriptions (plan_code);
CREATE INDEX IF NOT EXISTS ix_external_subscriptions_cycle ON public.external_subscriptions (cycle_code);

-- ── processed RevenueCat events ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.revenuecat_webhook_event (
    id                       VARCHAR(128)  PRIMARY KEY,
    type                     VARCHAR(64)   NOT NULL,
    app_user_id              VARCHAR(255),
    original_transaction_id  VARCHAR(100),
    environment              VARCHAR(20),
    event_timestamp_ms       BIGINT        NOT NULL DEFAULT 0,
    tenant_id                UUID
                             CONSTRAINT fk_revenuecat_webhook_event_tenant
                             REFERENCES public.tenants (id) ON DELETE SET NULL,
    outcome                  VARCHAR(30),
    -- purchase details, kept so a purchase left UNRESOLVED / UNKNOWN_PRODUCT is recorded in the ledger once the
    -- subscription is bound (owner sync, later event)
    transaction_id           VARCHAR(100),
    product_id               VARCHAR(100),
    price                    NUMERIC(14, 2),
    currency                 VARCHAR(3),
    period_type              VARCHAR(20),
    purchased_at             TIMESTAMPTZ,
    expires_at               TIMESTAMPTZ,
    processed_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- purchases waiting for their subscription to be bound (replayed into the ledger at binding)
CREATE INDEX IF NOT EXISTS ix_revenuecat_webhook_event_pending
    ON public.revenuecat_webhook_event (original_transaction_id)
    WHERE outcome IN ('UNRESOLVED', 'UNKNOWN_PRODUCT');

-- events of a company (support, ON DELETE SET NULL of a purged tenant)
CREATE INDEX IF NOT EXISTS ix_revenuecat_webhook_event_tenant
    ON public.revenuecat_webhook_event (tenant_id, processed_at DESC) WHERE tenant_id IS NOT NULL;

-- ── ledger: App Store purchases ───────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.tenant_payments ADD COLUMN IF NOT EXISTS store_transaction_id VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_payments_store_transaction
    ON public.tenant_payments (store_transaction_id) WHERE store_transaction_id IS NOT NULL;

-- same shape as V61 (NOT VALID then VALIDATE; small platform tables, inside the one Flyway transaction)
ALTER TABLE public.tenant_payments DROP CONSTRAINT IF EXISTS tenant_payments_method_check;
ALTER TABLE public.tenant_payments ADD CONSTRAINT tenant_payments_method_check
    CHECK (method IN ('MOBILE_MONEY', 'CASH', 'BANK_TRANSFER', 'OTHER', 'APP_STORE')) NOT VALID;
ALTER TABLE public.tenant_payments VALIDATE CONSTRAINT tenant_payments_method_check;

-- ── catalogue journal: + STORE_PRODUCT ────────────────────────────────────────────────────────────────────────
ALTER TABLE public.offer_change_log DROP CONSTRAINT IF EXISTS offer_change_log_entity_check;
ALTER TABLE public.offer_change_log ADD CONSTRAINT offer_change_log_entity_check
    CHECK (entity IN ('PLAN', 'CYCLE', 'PRICE', 'SETTINGS', 'DISCOUNT', 'STORE_PRODUCT')) NOT VALID;
ALTER TABLE public.offer_change_log VALIDATE CONSTRAINT offer_change_log_entity_check;
