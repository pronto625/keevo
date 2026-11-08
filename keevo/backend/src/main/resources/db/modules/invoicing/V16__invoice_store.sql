-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V16 (Story 20.14, FUS-FR24): the establishment of a document.
--
-- invoices.store_id — the store the document was made in, written once at creation (store member → its store,
-- owner → the requested or the oldest active store, quote conversion → the quote's, POS bridge → the sale's).
-- Credit notes have no column: they belong to the store of their invoice. Nullable, no foreign key (the stores
-- table belongs to the store module; a store is deactivated, never deleted). Existing rows keep NULL: no backfill,
-- no total nor number touched — they stay in the all-stores figures only.
--
-- Indexes of the reporting revenue source (RevenueRelations), same predicates as V14:
--   * idx_invoices_store_revenue_day — store-scoped revenue windows on the civil issue date (WAT midnight);
--   * idx_invoices_counted_issued_at — hourly store activity of the tenant (sales-trend job, one grouped query over
--                                      all stores) on the issue instant;
--   * idx_invoices_client            — per-client totals of the clients export (issued / paid, live rows: covers
--                                      counted invoices and the invoices whose credit notes are deducted).
-- Idempotent. Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- V15 is reserved for story 20-7 (built in parallel).
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V…__ script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS store_id UUID;

CREATE INDEX IF NOT EXISTS idx_invoices_store_revenue_day
    ON invoices (store_id, ((issue_date::timestamp AT TIME ZONE 'Africa/Lagos')))
    WHERE kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND source_sale_id IS NULL AND deleted_at IS NULL
      AND superseded_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_counted_issued_at
    ON invoices (issued_at)
    WHERE kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND source_sale_id IS NULL AND deleted_at IS NULL
      AND superseded_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_client
    ON invoices (client_id)
    WHERE client_id IS NOT NULL AND kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND deleted_at IS NULL
      AND superseded_at IS NULL;
