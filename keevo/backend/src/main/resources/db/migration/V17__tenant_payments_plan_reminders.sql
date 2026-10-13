-- V17 — Story 9-2: paid plan activation, payment history, plan change history, expiry reminders.
--
-- All three tables are global (public only): TenantSchemaSyncService lists them as global-only so
-- they are never mirrored into tenant schemas.
--
--   tenant_payments        off-platform payments recorded by the super admin (append-only; 9-3 MRR)
--   tenant_plan_events     every plan change (registration, admin activation/downgrade, expiry; 9-3 churn)
--   plan_expiry_reminders  idempotency key of the WhatsApp reminders sent 7 and 1 days before expiry

CREATE TABLE IF NOT EXISTS public.tenant_payments (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL
                    CONSTRAINT fk_tenant_payments_tenant
                    REFERENCES public.tenants (id) ON DELETE RESTRICT,  -- payments never vanish silently
    amount_xaf    INTEGER      NOT NULL
                    CONSTRAINT tenant_payments_amount_check CHECK (amount_xaf > 0),
    method        VARCHAR(20)  NOT NULL
                    CONSTRAINT tenant_payments_method_check
                    CHECK (method IN ('MOBILE_MONEY', 'CASH', 'BANK_TRANSFER', 'OTHER')),
    paid_at       TIMESTAMPTZ  NOT NULL,
    period_start  DATE         NOT NULL,
    period_end    DATE         NOT NULL,
    note          VARCHAR(500),
    recorded_by   UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_payments_period_check CHECK (period_end >= period_start)
);

CREATE INDEX IF NOT EXISTS idx_tenant_payments_tenant_paid
    ON public.tenant_payments (tenant_id, paid_at DESC);

CREATE TABLE IF NOT EXISTS public.tenant_plan_events (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL
                   CONSTRAINT fk_tenant_plan_events_tenant
                   REFERENCES public.tenants (id) ON DELETE CASCADE
                   -- checked at commit: the REGISTRATION row is written (JDBC) before Hibernate
                   -- flushes the new public.tenants row of the same registration transaction
                   DEFERRABLE INITIALLY DEFERRED,
    from_plan    VARCHAR(20)
                   CONSTRAINT tenant_plan_events_from_plan_check
                   CHECK (from_plan IN ('FREE', 'PREMIUM_TRIAL', 'PREMIUM')),
    to_plan      VARCHAR(20)  NOT NULL
                   CONSTRAINT tenant_plan_events_to_plan_check
                   CHECK (to_plan IN ('FREE', 'PREMIUM_TRIAL', 'PREMIUM')),
    cause        VARCHAR(20)  NOT NULL
                   CONSTRAINT tenant_plan_events_cause_check
                   CHECK (cause IN ('ADMIN_ACTIVATION', 'ADMIN_DOWNGRADE', 'EXPIRY', 'REGISTRATION')),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id     UUID,
    reason       VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_tenant_plan_events_tenant_occurred
    ON public.tenant_plan_events (tenant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_tenant_plan_events_occurred
    ON public.tenant_plan_events (occurred_at);

CREATE TABLE IF NOT EXISTS public.plan_expiry_reminders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL
                      CONSTRAINT fk_plan_expiry_reminders_tenant
                      REFERENCES public.tenants (id) ON DELETE CASCADE,
    threshold_days  INTEGER      NOT NULL,
    expiry_date     DATE         NOT NULL,
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plan_expiry_reminders UNIQUE (tenant_id, threshold_days, expiry_date)
);

-- One-time backfill: a REGISTRATION event per existing tenant. The current plan lives in each
-- tenant schema (unreadable cross-schema from plain SQL), so every tenant is recorded as having
-- started on the 6-month trial at its registration date. The system tenant (KV-ADMIN) is excluded.
INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, occurred_at)
SELECT t.id, NULL, 'PREMIUM_TRIAL', 'REGISTRATION', t.created_at
FROM public.tenants t
WHERE t.id <> '00000000-0000-0000-0000-000000000000'::uuid
  AND NOT EXISTS (
      SELECT 1 FROM public.tenant_plan_events e
      WHERE e.tenant_id = t.id AND e.cause = 'REGISTRATION');
