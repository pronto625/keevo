-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V14 (Story 20.9, R4): indexes of the reporting revenue source (RevenueQueryPort).
--
-- The revenue source counts an invoice on its civil issue date (WAT midnight of issue_date, FUS-NFR9) when it is a
-- counted invoice (kind INVOICE, ISSUED or PAID, not bridged from a sale, neither deleted nor superseded), and
-- deducts a credit note on its own issued_at. Reporting windows filter on exactly these expressions:
--   * idx_invoices_revenue_day — partial expression index, only the counted invoices (the expression is the one the
--     source selects as occurred_at, so the planner matches the pushed-down window predicate);
--   * idx_credit_notes_issued_at — credit notes deducted in a window.
-- No data change. Idempotent. Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V14__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_invoices_revenue_day
    ON invoices (((issue_date::timestamp AT TIME ZONE 'Africa/Lagos')))
    WHERE kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND source_sale_id IS NULL AND deleted_at IS NULL
      AND superseded_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_credit_notes_issued_at ON credit_notes (issued_at);
