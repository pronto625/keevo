-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V17 (Story 20.13, decision A6): « paiement reçu » events of the super-admin activity feed.
--
-- The feed reads, per tenant, the most recent received payments: invoice_payments rows with paid_at set, ordered by
-- paid_at DESC, LIMIT n. Without an index this is a scan of every payment row of the tenant; this partial index
-- serves it as an index range scan. (V15 / V16 are taken by stories 20-11 / 20-14.) Idempotent, no data change.
-- ─────────────────────────────────────────────────────────────────────────────
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_invoice_payments_paid_at
    ON invoice_payments (paid_at DESC) WHERE paid_at IS NOT NULL;
