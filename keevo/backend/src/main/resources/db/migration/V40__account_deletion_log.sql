-- V40 — Story 16.6 (FR91, RGPD): durable trace of every account deletion request, cancellation and execution.
--
--   account_deletion_log  one row per event: REQUESTED (owner request or admin force-delete), CANCELLED (owner or
--                         admin), EXECUTED (daily run = SYSTEM, admin force-delete = ADMIN).
--
-- The tenant audit_log rows of the request/cancel live in the tenant schema and are dropped with it; this table is
-- the trace that survives the purge. It is itself RGPD-clean on purpose: tenant id and account code only — no name,
-- no phone, no user id — and no foreign key (the tenants row is anonymised, never relied upon). Public schema only.
CREATE TABLE IF NOT EXISTS public.account_deletion_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL,
    tenant_code   VARCHAR(10)  NOT NULL,
    event         VARCHAR(20)  NOT NULL
                  CONSTRAINT account_deletion_log_event_check CHECK (event IN ('REQUESTED', 'CANCELLED', 'EXECUTED')),
    source        VARCHAR(10)  NOT NULL
                  CONSTRAINT account_deletion_log_source_check CHECK (source IN ('OWNER', 'ADMIN', 'SYSTEM')),
    scheduled_for TIMESTAMPTZ  NULL,
    -- clock_timestamp(), not now() (transaction start): events written close together still order correctly
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS idx_account_deletion_log_tenant_occurred
    ON public.account_deletion_log (tenant_id, occurred_at);

-- Support lookups by account code (KV-XXXXXX), the only identifier left once the tenant row is anonymised.
CREATE INDEX IF NOT EXISTS idx_account_deletion_log_tenant_code
    ON public.account_deletion_log (tenant_code);
