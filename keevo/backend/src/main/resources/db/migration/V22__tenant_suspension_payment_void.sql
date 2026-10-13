-- V22 — Story 9-2 review follow-up: lasting suspension trace, payment correction, realignment history.
--
--   tenants.suspended_*        who suspended the tenant, when and why (cleared on reactivation)
--   tenant_payments.voided_*   a payment entered by mistake is voided (never deleted); voided rows are
--                              excluded from every count (9-3 revenue, 9-2 renewal end)
--   tenant_plan_events.cause   + REALIGNMENT: the one-shot PlanTypeRealignJob records the correction of a
--                              stale public.tenants.plan_type (ignored by 9-3 churn and conversion)
--
-- All three tables are public-only (TenantSchemaSyncService global-only list).

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS suspended_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspended_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS suspended_by     UUID;

ALTER TABLE public.tenant_payments
    ADD COLUMN IF NOT EXISTS voided_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS voided_by   UUID,
    ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);

ALTER TABLE public.tenant_plan_events DROP CONSTRAINT IF EXISTS tenant_plan_events_cause_check;
ALTER TABLE public.tenant_plan_events ADD CONSTRAINT tenant_plan_events_cause_check
    CHECK (cause IN ('ADMIN_ACTIVATION', 'ADMIN_DOWNGRADE', 'EXPIRY', 'REGISTRATION', 'REALIGNMENT'));
