-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V10 (Story 20.4, fusion Keevo × Bref FUS-FR20): collections — deposit, partial payments,
-- payment schedule, overdue invoices.
--
-- Additive only, no data UPDATE:
--   * invoice_payments     — one row per payment received (paid_at set) or per scheduled instalment (due_date set,
--                            paid_at NULL until it is settled; a settled instalment keeps its due_date). "Exactly one
--                            of paid_at / due_date at insert" is enforced by the application (a settled instalment
--                            carries both); a row always has at least one of them. Amounts are XAF francs (> 0).
--                            Its id is chosen by the device (idempotency of ADD_INVOICE_PAYMENT).
--   * invoices.payment_state — UNPAID / PARTIAL / PAID / OVERDUE, recomputed by the application under the invoice row
--                            lock at every payment change, and by the daily overdue job. NULL on every existing row:
--                            read as PAID (status PAID) or UNPAID (status ISSUED) — legacy invoices are never rewritten.
--   * indexes              — payments of an invoice; unpaid instalments by due date (overdue job); invoices by
--                            payment state as listed (stored, else read through the status; list filter ?paymentState=).
-- Idempotent (IF NOT EXISTS, named constraints guarded). Applied lazily inside the tenant schema
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V11__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS invoice_payments (
    id          UUID          PRIMARY KEY,
    invoice_id  UUID          NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    amount      BIGINT        NOT NULL CHECK (amount > 0),
    due_date    DATE,
    paid_at     TIMESTAMPTZ,
    method      VARCHAR(60),
    note        VARCHAR(500),
    created_by  UUID,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT invoice_payments_dated_check CHECK (due_date IS NOT NULL OR paid_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_invoice_payments_invoice ON invoice_payments (invoice_id);

-- overdue job: unpaid instalments past their due date
CREATE INDEX IF NOT EXISTS idx_invoice_payments_unpaid_due ON invoice_payments (due_date)
    WHERE paid_at IS NULL;

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS payment_state VARCHAR(10);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_payment_state_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_payment_state_check
            CHECK (payment_state IS NULL OR payment_state IN ('UNPAID', 'PARTIAL', 'PAID', 'OVERDUE'));
    END IF;
END $$;

-- list filter ?paymentState= (JdbcInvoiceRepository.PAYMENT_STATE): the stored state, or the one read through the
-- status when never computed, ordered like the list — one index range, no sort
CREATE INDEX IF NOT EXISTS idx_invoices_payment_state_list
    ON invoices ((COALESCE(payment_state, CASE status WHEN 'PAID' THEN 'PAID' ELSE 'UNPAID' END)), created_at DESC,
                 id DESC)
    WHERE kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND deleted_at IS NULL;
