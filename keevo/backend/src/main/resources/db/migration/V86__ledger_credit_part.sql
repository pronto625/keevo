-- V86 — Story 24.5 (item routed by the 21.3 review): the part of a plan paid with the account credit in the ledger.
--
--   tenant_payments.credit_xaf   the part of the price paid with the account credit (FCFA, >= 0). The amount stays the
--                                cash collected (now >= 0): a plan paid 100 % with the credit is one row with amount 0
--                                and method ACCOUNT_CREDIT. The super-admin MRR counts cash + credit, the revenue
--                                collected counts cash only (admin/revenue).
--   tenant_payments.method       + ACCOUNT_CREDIT.
--
-- The Bref import (24.5) writes the credit part of the plans its owners paid. Platform data, public schema only.
-- Idempotent (IF NOT EXISTS / DROP … IF EXISTS). The new column has a constant default (no table rewrite); the checks
-- are added NOT VALID then validated (same shape as V61/V62; small platform table).
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.tenant_payments ADD COLUMN IF NOT EXISTS credit_xaf BIGINT NOT NULL DEFAULT 0;

ALTER TABLE public.tenant_payments DROP CONSTRAINT IF EXISTS tenant_payments_credit_check;
ALTER TABLE public.tenant_payments ADD CONSTRAINT tenant_payments_credit_check CHECK (credit_xaf >= 0) NOT VALID;
ALTER TABLE public.tenant_payments VALIDATE CONSTRAINT tenant_payments_credit_check;

ALTER TABLE public.tenant_payments DROP CONSTRAINT IF EXISTS tenant_payments_amount_check;
ALTER TABLE public.tenant_payments ADD CONSTRAINT tenant_payments_amount_check
    CHECK (amount_xaf >= 0 AND amount_xaf + credit_xaf > 0) NOT VALID;
ALTER TABLE public.tenant_payments VALIDATE CONSTRAINT tenant_payments_amount_check;

ALTER TABLE public.tenant_payments DROP CONSTRAINT IF EXISTS tenant_payments_method_check;
ALTER TABLE public.tenant_payments ADD CONSTRAINT tenant_payments_method_check
    CHECK (method IN ('MOBILE_MONEY', 'CASH', 'BANK_TRANSFER', 'OTHER', 'APP_STORE', 'ACCOUNT_CREDIT')) NOT VALID;
ALTER TABLE public.tenant_payments VALIDATE CONSTRAINT tenant_payments_method_check;
