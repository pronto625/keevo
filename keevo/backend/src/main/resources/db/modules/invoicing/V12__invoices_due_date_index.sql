-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V12 (Story 20.5): the overdue job's index of issued invoices past their own due date, limited to
-- the rows the job can mark (active: neither deleted nor superseded). Replaces V11's idx_invoices_issued_due_date.
-- No data change. Idempotent. Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V13__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

DROP INDEX IF EXISTS idx_invoices_issued_due_date;

CREATE INDEX IF NOT EXISTS idx_invoices_issued_due_date_active ON invoices (due_date)
    WHERE kind = 'INVOICE' AND status = 'ISSUED' AND due_date IS NOT NULL AND deleted_at IS NULL
      AND superseded_at IS NULL;
