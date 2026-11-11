-- Story 24.9 (ADR-10) — helper indexes on the RESTORED COPY of the Bref database, never on Bref itself.
--
-- Bref has no index on the columns the migration tool reads owner by owner (shop."userId", the text "shopId" columns
-- compared trimmed and lower-cased, invoice_item / invoice_payment "invoiceId", the monetisation owner columns): without
-- them every owner scans those tables, O(owners × table) over the whole cutover. With them every read of an owner is an
-- index lookup: the cutover is O(total rows).
--
-- Run it as the owner of the restored database (psql -d <copie> -f bref-copy-indexes.sql), right after the restore and
-- BEFORE granting the read-only role to the tool (runbook docs/RUNBOOK_BASCULE_BREF.md §3). Idempotent. The cutover
-- report shows « index de la copie : n / 11 ».
\set ON_ERROR_STOP on

CREATE INDEX IF NOT EXISTS keevo_bref_shop_user ON shop ("userId");
CREATE INDEX IF NOT EXISTS keevo_bref_user_shop ON "user" (lower(btrim("shopId")));
CREATE INDEX IF NOT EXISTS keevo_bref_product_shop ON product (lower(btrim("shopId")));
CREATE INDEX IF NOT EXISTS keevo_bref_invoice_shop ON invoice (lower(btrim("shopId")));
CREATE INDEX IF NOT EXISTS keevo_bref_invoice_item_invoice ON invoice_item ("invoiceId");
CREATE INDEX IF NOT EXISTS keevo_bref_invoice_payment_invoice ON invoice_payment ("invoiceId");
CREATE INDEX IF NOT EXISTS keevo_bref_expense_shop ON expense (lower(btrim("shopId")));
CREATE INDEX IF NOT EXISTS keevo_bref_subscription_owner ON subscription ("ownerId");

-- tables an older Bref schema may lack
DO $$
BEGIN
    IF to_regclass('public.plan_payment_request') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS keevo_bref_plan_request_owner ON plan_payment_request ("ownerId")';
    END IF;
    -- Story 24.6 item: the assistant's conversations are read by "userId" (no index in Bref)
    IF to_regclass('public.ai_conversation') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS keevo_bref_ai_conversation_user ON ai_conversation ("userId")';
    END IF;
    IF to_regclass('public.sms_recharge_request') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS keevo_bref_sms_recharge_user ON sms_recharge_request ("userId")';
    END IF;
END $$;

ANALYZE;
